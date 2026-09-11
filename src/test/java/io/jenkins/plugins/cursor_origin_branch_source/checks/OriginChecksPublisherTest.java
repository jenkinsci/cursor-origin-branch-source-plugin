package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksAnnotation;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import io.jenkins.plugins.cursor_origin_branch_source.MockOriginServer;
import io.jenkins.plugins.cursor_origin_branch_source.MockOriginServerTestBase;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import io.jenkins.plugins.util.PluginLogger;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMHead;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the publisher against {@link MockOriginServer}, which serves the real Origin wire format
 * and enforces the parts of its contract a publisher has to get right. Authentication is real too: the
 * publisher mints a genuine installation token from the app credentials the harness registers.
 */
@SuppressWarnings("rawtypes")
class OriginChecksPublisherTest extends MockOriginServerTestBase {

    private static final String REPOSITORY = "widgets";
    private static final String SHA = "aaaa1111";
    private static final String RUN_URL = "http://localhost:8080/job/widgets/job/main/3/";

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    @BeforeEach
    void addRepository() {
        mockServer.addRepo(OWNER, REPOSITORY, "main").branch("main", SHA);
    }

    @Test
    void upsertsAQueuedCheckRun() {
        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.QUEUED)
                .build());

        MockOriginServer.MockCheckRun checkRun = mockServer.checkRun(OWNER, REPOSITORY, "Jenkins");
        assertThat(checkRun.getName(), is("Jenkins"));
        assertThat(checkRun.getStatus(), is("queued"));
        assertThat(checkRun.getConclusion(), is(nullValue()));
        assertThat(checkRun.getHeadSha(), is(SHA));
        assertThat(checkRun.getSuiteKey(), is("widgets"));
        assertThat(checkRun.getExternalId(), is("widgets/main#3"));
        assertThat(checkRun.getDetailsUrl(), is(RUN_URL));
        assertThat(
                consoleLog(),
                containsString("Cursor Origin check (name: Jenkins, status: queued) has been published."));
    }

    /**
     * Cursor Origin rejects a conclusion reported before the check run has completed, so the mock
     * server would answer 400 if the publisher passed one on.
     */
    @Test
    void reportsAnInProgressCheckRunWithoutAConclusion() {
        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.IN_PROGRESS)
                .withConclusion(ChecksConclusion.SUCCESS)
                .withStartedAt(LocalDateTime.of(2026, 9, 4, 12, 0))
                .build());

        MockOriginServer.MockCheckRun checkRun = mockServer.checkRun(OWNER, REPOSITORY, "Jenkins");
        assertThat(checkRun.getStatus(), is("in_progress"));
        assertThat(checkRun.getConclusion(), is(nullValue()));
        assertThat(checkRun.getStartedAt(), is("2026-09-04T12:00:00Z"));
        assertThat(checkRun.getCompletedAt(), is(nullValue()));
    }

    @Test
    void reportsACompletedCheckRunWithItsConclusionAndOutput() {
        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(ChecksConclusion.FAILURE)
                .withStartedAt(LocalDateTime.of(2026, 9, 4, 12, 0))
                .withCompletedAt(LocalDateTime.of(2026, 9, 4, 12, 5))
                .withDetailsURL("https://ci.example.com/build/3")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle("Failure")
                        .withSummary("2 of 128 tests failed.")
                        .withText("See the build log.")
                        .build())
                .build());

        MockOriginServer.MockCheckRun checkRun = mockServer.checkRun(OWNER, REPOSITORY, "Jenkins");
        assertThat(checkRun.getStatus(), is("completed"));
        assertThat(checkRun.getConclusion(), is("failure"));
        assertThat(checkRun.getStartedAt(), is("2026-09-04T12:00:00Z"));
        assertThat(checkRun.getCompletedAt(), is("2026-09-04T12:05:00Z"));
        assertThat(checkRun.getDetailsUrl(), is("https://ci.example.com/build/3"));
        assertThat(checkRun.getOutputTitle(), is("Failure"));
        assertThat(checkRun.getOutputSummary(), is("2 of 128 tests failed."));
        assertThat(checkRun.getOutputText(), is("See the build log."));
    }

    /**
     * Origin matches a repeated report on the suite and check keys, so the lifecycle of one check has
     * to update a single check run rather than accumulate duplicates.
     */
    @Test
    void updatesOneCheckRunAcrossTheLifecycleOfACheck() {
        OriginChecksPublisher publisher = createPublisher(mockRun(mockJob()));

        publisher.publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.QUEUED)
                .build());
        publisher.publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.IN_PROGRESS)
                .build());
        publisher.publish(completed("Jenkins"));

        assertThat(mockServer.checkRuns(OWNER, REPOSITORY), hasSize(1));
        assertThat(
                mockServer.checkRun(OWNER, REPOSITORY, "Jenkins").reportedStates(),
                contains("queued", "in_progress", "completed/success"));
    }

    @Test
    void reportsEachCheckOfABuildSeparatelyWithinOneSuite() {
        OriginChecksPublisher publisher = createPublisher(mockRun(mockJob()));

        publisher.publish(completed("unit-tests"));
        publisher.publish(completed("integration-tests"));

        List<MockOriginServer.MockCheckRun> checkRuns = mockServer.checkRuns(OWNER, REPOSITORY);
        assertThat(checkRuns, hasSize(2));
        assertThat(
                checkRuns.stream()
                        .map(MockOriginServer.MockCheckRun::getSuiteKey)
                        .distinct()
                        .toList(),
                contains("widgets"));
    }

    @Test
    void appendsAnnotationsToThePublishedCheckRun() {
        publish(detailsWithAnnotations(2));

        List<MockOriginServer.MockAnnotation> annotations =
                mockServer.checkRun(OWNER, REPOSITORY, "Jenkins").getAnnotations();
        assertThat(annotations, hasSize(2));
        assertThat(annotations.get(0).level(), is("warning"));
        assertThat(annotations.get(0).message(), is("message 0"));
        assertThat(annotations.get(0).path(), is("Example.java"));
        assertThat(annotations.get(0).startLine(), is(1));
        assertThat(annotations.get(1).message(), is("message 1"));
        assertThat(annotations.get(1).startLine(), is(2));
    }

    /** The mock server rejects an oversized batch, so unbatched annotations would fail this test. */
    @Test
    void splitsAnnotationsIntoTheBatchesOriginAccepts() {
        int count = OriginChecksPublisher.ANNOTATION_BATCH_SIZE + 3;

        publish(detailsWithAnnotations(count));

        assertThat(mockServer.checkRun(OWNER, REPOSITORY, "Jenkins").getAnnotations(), hasSize(count));
    }

    @Test
    void dropsAnnotationsBeyondWhatACheckRunCanStore() {
        publish(detailsWithAnnotations(OriginChecksPublisher.MAX_ANNOTATIONS_PER_CHECK_RUN + 10));

        assertThat(
                mockServer.checkRun(OWNER, REPOSITORY, "Jenkins").getAnnotations(),
                hasSize(OriginChecksPublisher.MAX_ANNOTATIONS_PER_CHECK_RUN));
        assertThat(consoleLog(), containsString("dropping 10 of 110 annotations of check 'Jenkins'"));
    }

    /**
     * Annotations are appended rather than replaced, so a second report of the same check must only
     * send the ones Origin has not seen yet.
     */
    @Test
    void doesNotResendAnnotationsOnASecondReportOfTheSameCheck() {
        Run run = mockRun(mockJob());
        recordActionsOn(run);
        OriginChecksPublisher publisher = createPublisher(run);

        publisher.publish(detailsWithAnnotations(2));
        publisher.publish(detailsWithAnnotations(3));

        assertThat(mockServer.checkRun(OWNER, REPOSITORY, "Jenkins").getAnnotations(), hasSize(3));
    }

    @Test
    void publishesTheCheckRunEvenWhenItsAnnotationsAreRejected() {
        mockServer.rejectAnnotationsWith(429);

        publish(detailsWithAnnotations(1));

        MockOriginServer.MockCheckRun checkRun = mockServer.checkRun(OWNER, REPOSITORY, "Jenkins");
        assertThat(checkRun.getStatus(), is("completed"));
        assertThat(checkRun.getAnnotations(), is(empty()));
        assertThat(consoleLog(), containsString("Failed publishing 1 annotations of Cursor Origin check 'Jenkins'"));
    }

    /** A failure to report must never fail the build that is being reported on. */
    @Test
    void reportsRatherThanThrowsWhenOriginRejectsTheRequest() {
        OriginChecksPublisher publisher = createPublisher(mockRun(mockJob()), "unknown-repo");

        publisher.publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.QUEUED)
                .build());

        assertThat(consoleLog(), containsString("Failed publishing Cursor Origin checks"));
        assertThat(mockServer.checkRuns(OWNER, REPOSITORY), is(empty()));
    }

    @Test
    void reportsRatherThanThrowsWhenTheCheckItselfIsInvalid() {
        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withStatus(ChecksStatus.QUEUED)
                .build());

        assertThat(mockServer.checkRuns(OWNER, REPOSITORY), is(empty()));
        assertThat(consoleLog(), containsString("The check name is blank."));
    }

    private static ChecksDetails completed(String name) {
        return new ChecksDetails.ChecksDetailsBuilder()
                .withName(name)
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(ChecksConclusion.SUCCESS)
                .build();
    }

    private static ChecksDetails detailsWithAnnotations(int count) {
        ChecksOutput.ChecksOutputBuilder output =
                new ChecksOutput.ChecksOutputBuilder().withTitle("title").withSummary("summary");
        for (int i = 0; i < count; i++) {
            output.addAnnotation(new ChecksAnnotation.ChecksAnnotationBuilder()
                    .withPath("Example.java")
                    .withLine(i + 1)
                    .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                    .withMessage("message " + i)
                    .build());
        }
        return new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(ChecksConclusion.SUCCESS)
                .withOutput(output.build())
                .build();
    }

    private void publish(ChecksDetails details) {
        createPublisher(mockRun(mockJob())).publish(details);
    }

    private OriginChecksPublisher createPublisher(Run run) {
        return createPublisher(run, REPOSITORY);
    }

    private OriginChecksPublisher createPublisher(Run run, String repository) {
        PluginLogger logger =
                new PluginLogger(new PrintStream(console, true, StandardCharsets.UTF_8), "Cursor Origin Checks");
        return new OriginChecksPublisher(createContext(run, repository), logger);
    }

    /**
     * Builds a context for a mocked job, with the SCM lookups stubbed but the credentials, token
     * minting and HTTP traffic all real.
     */
    private OriginChecksContext createContext(Run run, String repository) {
        OriginSCMSource source = new OriginSCMSource(OWNER, repository);
        source.setCredentialsId(CREDS_ID);
        OriginSCMFacade facade = mock(OriginSCMFacade.class);
        when(facade.findOriginSCMSource(run.getParent())).thenReturn(Optional.of(source));
        when(facade.findRevision(source, run))
                .thenReturn(Optional.of(new AbstractGitSCMSource.SCMRevisionImpl(new SCMHead("main"), SHA)));
        when(facade.findHash(any()))
                .thenAnswer(invocation -> new OriginSCMFacade().findHash(invocation.getArgument(0)));
        when(facade.findCredentials(run.getParent(), CREDS_ID)).thenReturn(Optional.of(credentials()));
        return OriginChecksContext.fromRun(run, RUN_URL, facade);
    }

    private static Job mockJob() {
        Job job = mock(Job.class);
        when(job.getFullName()).thenReturn("widgets");
        when(job.getFullDisplayName()).thenReturn("widgets");
        return job;
    }

    private static Run mockRun(Job job) {
        Run run = mock(Run.class);
        when(run.getParent()).thenReturn(job);
        when(run.getExternalizableId()).thenReturn("widgets/main#3");
        when(run.getActions(OriginChecksAction.class)).thenReturn(List.of());
        return run;
    }

    /** Makes a mocked run remember the actions added to it, as a real run would. */
    private static void recordActionsOn(Run run) {
        List<OriginChecksAction> actions = new ArrayList<>();
        doAnswer(invocation -> {
                    if (invocation.getArgument(0) instanceof OriginChecksAction action) {
                        actions.add(action);
                    }
                    return null;
                })
                .when(run)
                .addAction(any());
        when(run.getActions(OriginChecksAction.class)).thenReturn(actions);
    }

    private String consoleLog() {
        return console.toString(StandardCharsets.UTF_8);
    }
}
