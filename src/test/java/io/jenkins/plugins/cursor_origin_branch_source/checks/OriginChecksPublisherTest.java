package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

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
import java.util.List;
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
        // The source has no owner here, so the suite key falls back to the job's full name;
        // OriginChecksITest covers the multibranch case where the owner supplies it.
        assertThat(checkRun.getSuiteKey(), is("widgets/main"));
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
        OriginChecksPublisher publisher = createPublisher(fakeRun());

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
        OriginChecksPublisher publisher = createPublisher(fakeRun());

        publisher.publish(completed("unit-tests"));
        publisher.publish(completed("integration-tests"));

        List<MockOriginServer.MockCheckRun> checkRuns = mockServer.checkRuns(OWNER, REPOSITORY);
        assertThat(checkRuns, hasSize(2));
        assertThat(
                checkRuns.stream()
                        .map(MockOriginServer.MockCheckRun::getSuiteKey)
                        .distinct()
                        .toList(),
                contains("widgets/main"));
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
     * The mock server enforces Origin's size limits, so an untruncated oversized annotation would be
     * rejected and lost along with the rest of its batch. It has to arrive shortened instead, and the
     * build log has to say so, since the reader is otherwise looking at silently altered output.
     */
    @Test
    void publishesAnOversizedAnnotationTruncatedAndSaysSo() {
        ChecksOutput.ChecksOutputBuilder output =
                new ChecksOutput.ChecksOutputBuilder().withTitle("title").withSummary("summary");
        output.addAnnotation(new ChecksAnnotation.ChecksAnnotationBuilder()
                .withPath("Example.java")
                .withLine(1)
                .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                .withMessage("m".repeat(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES + 1000))
                .build());

        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(ChecksConclusion.SUCCESS)
                .withOutput(output.build())
                .build());

        List<MockOriginServer.MockAnnotation> published =
                mockServer.checkRun(OWNER, REPOSITORY, "Jenkins").getAnnotations();
        assertThat(published, hasSize(1));
        assertThat(
                published.get(0).message().getBytes(StandardCharsets.UTF_8).length,
                is(lessThanOrEqualTo(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES)));
        assertThat(published.get(0).message(), endsWith(OriginChecksDetails.TRUNCATION_MARKER));
        assertThat(consoleLog(), containsString("Truncated the message of 1 annotation(s)"));
    }

    /**
     * Annotations are appended rather than replaced, so a second report of the same check must only
     * send the ones Origin has not seen yet.
     */
    @Test
    void doesNotResendAnnotationsOnASecondReportOfTheSameCheck() {
        FakeRun run = fakeRun();
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
        OriginChecksPublisher publisher = createPublisher(fakeRun(), "unknown-repo");

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
        createPublisher(fakeRun()).publish(details);
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
     * Builds a context whose SCM lookups are supplied, but whose credentials, token minting and HTTP
     * traffic are all real.
     */
    private OriginChecksContext createContext(Run run, String repository) {
        OriginSCMSource source = new OriginSCMSource(OWNER, repository);
        source.setCredentialsId(CREDS_ID);
        FakeOriginSCMFacade facade = new FakeOriginSCMFacade()
                .withSource(source)
                .withRevision(new AbstractGitSCMSource.SCMRevisionImpl(new SCMHead("main"), SHA))
                .withCredentials(credentials());
        return OriginChecksContext.fromRun(run, RUN_URL, facade);
    }

    /**
     * A build of the {@code main} branch job of a {@code widgets} project, so the identities the
     * publisher sends are consistent with each other rather than stubbed independently. Actions are
     * held by {@code Actionable} itself, so a real run records the ones the publisher attaches without
     * any help from the test.
     */
    private static FakeRun fakeRun() {
        return new FakeRun(new FakeJob("widgets", "main"), 3);
    }

    private String consoleLog() {
        return console.toString(StandardCharsets.UTF_8);
    }
}
