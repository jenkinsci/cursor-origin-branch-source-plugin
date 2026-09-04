package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.jsonResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksAnnotation;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiClient;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies the requests the publisher makes against a stubbed Cursor Origin API, using the real
 * generated API client so that the wire format is exercised end to end.
 */
@SuppressWarnings("rawtypes")
class OriginChecksPublisherTest {

    @RegisterExtension
    private static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    private static final String OWNER = "acme-corp";
    private static final String REPOSITORY = "widgets";
    private static final String SHA = "9a41f0c3d2b8e7f6a5c4d3e2f1b0a9c8d7e6f5a4";
    private static final String CHECK_RUNS = "/v1/origin/repos/" + OWNER + "/" + REPOSITORY + "/check-runs";
    private static final String ANNOTATIONS = CHECK_RUNS + "/cr_1/annotations";
    private static final String RUN_URL = "http://localhost:8080/job/widgets/job/main/3/";

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    @Test
    void upsertsAQueuedCheckRun() {
        stubCheckRun();

        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.QUEUED)
                .build());

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo(CHECK_RUNS))
                .withRequestBody(equalToJson("""
                        {
                          "headSha": "%s",
                          "checkSuite": {
                            "key": "widgets",
                            "name": "widgets",
                            "externalId": "widgets/main#3",
                            "detailsUrl": "%s"
                          },
                          "checkRun": {
                            "key": "Jenkins",
                            "name": "Jenkins",
                            "status": "queued",
                            "externalId": "widgets/main#3",
                            "detailsUrl": "%s"
                          }
                        }
                        """.formatted(SHA, RUN_URL, RUN_URL), true, true))
                // Orders concurrent updates, so it has to be sent on every report.
                .withRequestBody(matchingJsonPath("$.checkRun.externalUpdatedAt")));
        assertThat(
                consoleLog(),
                containsString("Cursor Origin check (name: Jenkins, status: queued) has been published."));
    }

    /** Cursor Origin rejects a conclusion that is reported before the check run has completed. */
    @Test
    void reportsAnInProgressCheckRunWithoutAConclusion() {
        stubCheckRun();

        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.IN_PROGRESS)
                .withStartedAt(LocalDateTime.of(2026, 9, 4, 12, 0))
                .build());

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo(CHECK_RUNS))
                .withRequestBody(matchingJsonPath("$.checkRun.status", equalTo("in_progress")))
                .withRequestBody(matchingJsonPath("$.checkRun.startedAt")));
        assertThat(lastCheckRunRequestBody(), is(not(containsString("conclusion"))));
    }

    @Test
    void reportsACompletedCheckRunWithItsConclusionAndOutput() {
        stubCheckRun();

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

        WIRE_MOCK.verify(
                postRequestedFor(urlEqualTo(CHECK_RUNS)).withRequestBody(equalToJson("""
                        {
                          "headSha": "%s",
                          "checkRun": {
                            "key": "Jenkins",
                            "name": "Jenkins",
                            "status": "completed",
                            "conclusion": "failure",
                            "startedAt": "2026-09-04T12:00:00Z",
                            "completedAt": "2026-09-04T12:05:00Z",
                            "detailsUrl": "https://ci.example.com/build/3",
                            "output": {
                              "title": "Failure",
                              "summary": "2 of 128 tests failed.",
                              "text": "See the build log."
                            }
                          }
                        }
                        """.formatted(SHA), true, true)));
    }

    /** Repeated reports of the same check must upsert, which Origin keys on the suite and check keys. */
    @Test
    void keepsTheKeysStableAcrossTheLifecycleOfACheck() {
        stubCheckRun();
        OriginChecksPublisher publisher = createPublisher(mockContext(mockRun(mockJob())));

        publisher.publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.IN_PROGRESS)
                .build());
        publisher.publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(ChecksConclusion.SUCCESS)
                .build());

        WIRE_MOCK.verify(
                exactly(2),
                postRequestedFor(urlEqualTo(CHECK_RUNS))
                        .withRequestBody(matchingJsonPath("$.checkSuite.key", equalTo("widgets")))
                        .withRequestBody(matchingJsonPath("$.checkRun.key", equalTo("Jenkins"))));
    }

    @Test
    void appendsAnnotationsToThePublishedCheckRun() {
        stubCheckRun();
        stubAnnotations();

        publish(detailsWithAnnotations(2));

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo(ANNOTATIONS)).withRequestBody(equalToJson("""
                        {
                          "annotations": [
                            {
                              "annotationLevel": "warning",
                              "message": "message 0",
                              "location": {"path": "Example.java", "startLine": 1, "endLine": 1}
                            },
                            {
                              "annotationLevel": "warning",
                              "message": "message 1",
                              "location": {"path": "Example.java", "startLine": 2, "endLine": 2}
                            }
                          ]
                        }
                        """, false, true)));
    }

    @Test
    void splitsAnnotationsIntoTheBatchesOriginAccepts() {
        stubCheckRun();
        stubAnnotations();

        publish(detailsWithAnnotations(OriginChecksPublisher.ANNOTATION_BATCH_SIZE + 3));

        WIRE_MOCK.verify(exactly(2), postRequestedFor(urlEqualTo(ANNOTATIONS)));
        assertThat(annotationCountsOfRequests(), is(List.of(OriginChecksPublisher.ANNOTATION_BATCH_SIZE, 3)));
    }

    @Test
    void dropsAnnotationsBeyondWhatACheckRunCanStore() {
        stubCheckRun();
        stubAnnotations();

        publish(detailsWithAnnotations(OriginChecksPublisher.MAX_ANNOTATIONS_PER_CHECK_RUN + 10));

        assertThat(
                annotationCountsOfRequests().stream()
                        .mapToInt(Integer::intValue)
                        .sum(),
                is(OriginChecksPublisher.MAX_ANNOTATIONS_PER_CHECK_RUN));
        assertThat(consoleLog(), containsString("dropping 10 of 110 annotations of check 'Jenkins'"));
    }

    /**
     * Annotations are appended rather than replaced, so a second report of the same check must only
     * send the ones Origin has not seen yet.
     */
    @Test
    void doesNotResendAnnotationsOnASecondReportOfTheSameCheck() {
        stubCheckRun();
        stubAnnotations();
        Run run = mockRun(mockJob());
        List<Object> actions = new ArrayList<>();
        recordActionsOn(run, actions);
        OriginChecksPublisher publisher = createPublisher(mockContext(run));

        publisher.publish(detailsWithAnnotations(2));
        publisher.publish(detailsWithAnnotations(3));

        assertThat(annotationCountsOfRequests(), is(List.of(2, 1)));
        assertThat(actions, hasSize(1));
    }

    @Test
    void publishesTheCheckRunEvenWhenItsAnnotationsAreRejected() {
        stubCheckRun();
        WIRE_MOCK.stubFor(post(urlEqualTo(ANNOTATIONS))
                .willReturn(jsonResponse("{\"code\": 8, \"message\": \"annotation limit reached\"}", 429)));

        publish(detailsWithAnnotations(1));

        WIRE_MOCK.verify(exactly(1), postRequestedFor(urlEqualTo(CHECK_RUNS)));
        assertThat(consoleLog(), containsString("Failed publishing 1 annotations of Cursor Origin check 'Jenkins'"));
    }

    /** A failure to report must never fail the build that is being reported on. */
    @Test
    void reportsRatherThanThrowsWhenOriginRejectsTheRequest() {
        WIRE_MOCK.stubFor(post(urlEqualTo(CHECK_RUNS))
                .willReturn(jsonResponse("{\"code\": 7, \"message\": \"checks writes are not permitted\"}", 403)));

        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withName("Jenkins")
                .withStatus(ChecksStatus.QUEUED)
                .build());

        assertThat(consoleLog(), containsString("Failed publishing Cursor Origin checks"));
    }

    @Test
    void reportsRatherThanThrowsWhenTheCheckItselfIsInvalid() {
        stubCheckRun();

        publish(new ChecksDetails.ChecksDetailsBuilder()
                .withStatus(ChecksStatus.QUEUED)
                .build());

        WIRE_MOCK.verify(exactly(0), postRequestedFor(urlEqualTo(CHECK_RUNS)));
        assertThat(consoleLog(), containsString("The check name is blank."));
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
        createPublisher(mockContext(mockRun(mockJob()))).publish(details);
    }

    private OriginChecksPublisher createPublisher(OriginChecksContext context) {
        PluginLogger logger =
                new PluginLogger(new PrintStream(console, true, StandardCharsets.UTF_8), "Cursor Origin Checks");
        return new OriginChecksPublisher(context, logger, ignored -> api());
    }

    private static OriginServiceApi api() {
        ApiClient client = new ApiClient();
        client.updateBaseUri(WIRE_MOCK.baseUrl());
        return new OriginServiceApi(client);
    }

    private static void stubCheckRun() {
        WIRE_MOCK.stubFor(post(urlEqualTo(CHECK_RUNS))
                .willReturn(okJson("{\"checkSuite\": {\"id\": \"crg_1\"}, \"checkRun\": {\"id\": \"cr_1\"}}")));
    }

    private static void stubAnnotations() {
        WIRE_MOCK.stubFor(post(urlEqualTo(ANNOTATIONS)).willReturn(okJson("{\"annotations\": []}")));
    }

    private static String lastCheckRunRequestBody() {
        List<com.github.tomakehurst.wiremock.verification.LoggedRequest> requests =
                WIRE_MOCK.findAll(postRequestedFor(urlEqualTo(CHECK_RUNS)));
        return requests.get(requests.size() - 1).getBodyAsString();
    }

    /** The number of annotations sent by each annotation request, in request order. */
    private static List<Integer> annotationCountsOfRequests() {
        return WIRE_MOCK.findAll(postRequestedFor(urlEqualTo(ANNOTATIONS))).stream()
                .map(request -> request.getBodyAsString().split("\"annotationLevel\"", -1).length - 1)
                .toList();
    }

    private static OriginChecksContext mockContext(Run run) {
        OriginSCMSource source = new OriginSCMSource(OWNER, REPOSITORY);
        source.setCredentialsId("origin-creds");
        OriginSCMFacade facade = mock(OriginSCMFacade.class);
        when(facade.findOriginSCMSource(run.getParent())).thenReturn(Optional.of(source));
        when(facade.findRevision(source, run))
                .thenReturn(Optional.of(new AbstractGitSCMSource.SCMRevisionImpl(new SCMHead("main"), SHA)));
        when(facade.findHash(any()))
                .thenAnswer(invocation -> new OriginSCMFacade().findHash(invocation.getArgument(0)));
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
    private static void recordActionsOn(Run run, List<Object> actions) {
        doAnswer(invocation -> {
                    actions.add(invocation.getArgument(0));
                    return null;
                })
                .when(run)
                .addAction(any());
        when(run.getActions(OriginChecksAction.class))
                .thenAnswer(invocation -> actions.stream()
                        .filter(OriginChecksAction.class::isInstance)
                        .map(OriginChecksAction.class::cast)
                        .toList());
    }

    private String consoleLog() {
        return console.toString(StandardCharsets.UTF_8);
    }
}
