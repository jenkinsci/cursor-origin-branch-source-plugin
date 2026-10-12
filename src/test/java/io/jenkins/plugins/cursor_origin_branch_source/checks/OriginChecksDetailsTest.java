package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jenkins.plugins.checks.api.ChecksAnnotation;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunAnnotationInput;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunInput;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunOutput;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OriginChecksDetailsTest {

    @Test
    void nameIsUsedAsTheCheckRunName() {
        OriginChecksDetails details = new OriginChecksDetails(
                new ChecksDetails.ChecksDetailsBuilder().withName("unit-tests").build());

        assertThat(details.getName(), is("unit-tests"));
    }

    @Test
    void blankNameIsRejected() {
        ChecksDetails details =
                new ChecksDetails.ChecksDetailsBuilder().withName("  ").build();

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new OriginChecksDetails(details).getName());
        assertThat(e.getMessage(), is("The check name is blank."));
    }

    @ParameterizedTest(name = "{0} is reported as {1}")
    @CsvSource({"NONE,queued", "QUEUED,queued", "IN_PROGRESS,in_progress", "COMPLETED,completed"})
    void statusIsMapped(ChecksStatus status, String expected) {
        ChecksDetails.ChecksDetailsBuilder builder =
                new ChecksDetails.ChecksDetailsBuilder().withName("check").withStatus(status);
        if (status == ChecksStatus.COMPLETED) {
            builder.withConclusion(ChecksConclusion.SUCCESS);
        }

        assertThat(new OriginChecksDetails(builder.build()).getStatus().getValue(), is(expected));
    }

    @ParameterizedTest(name = "{0} is reported as {1}")
    @CsvSource({
        "SUCCESS,success",
        "FAILURE,failure",
        "NEUTRAL,neutral",
        "CANCELED,cancelled",
        "TIME_OUT,timed_out",
        "SKIPPED,skipped",
        "ACTION_REQUIRED,action_required"
    })
    void conclusionIsMapped(ChecksConclusion conclusion, String expected) {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(conclusion)
                .build());

        assertThat(details.getConclusion().map(CheckRunInput.ConclusionEnum::getValue), is(Optional.of(expected)));
    }

    /** Cursor Origin rejects a conclusion on a check run that has not completed. */
    @Test
    void conclusionIsOmittedWhileTheCheckIsStillRunning() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withStatus(ChecksStatus.IN_PROGRESS)
                .withConclusion(ChecksConclusion.SUCCESS)
                .build());

        assertThat(details.getConclusion(), is(Optional.empty()));
    }

    @Test
    void completedWithoutConclusionIsRejected() {
        ChecksDetails details = new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withStatus(ChecksStatus.COMPLETED)
                .build();

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new OriginChecksDetails(details));
        assertThat(e.getMessage(), is("No conclusion has been set when status is completed."));
    }

    @Test
    void completionTimeWithoutConclusionIsRejected() {
        ChecksDetails details = new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withCompletedAt(LocalDateTime.of(2026, 9, 4, 12, 0))
                .build();

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new OriginChecksDetails(details));
        assertThat(e.getMessage(), is("No conclusion has been set when \"completedAt\" is provided."));
    }

    @Test
    void timestampsAreReportedAsUtc() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(ChecksConclusion.SUCCESS)
                .withStartedAt(LocalDateTime.of(2026, 9, 4, 12, 0, 0))
                .withCompletedAt(LocalDateTime.of(2026, 9, 4, 12, 5, 30))
                .build());

        assertThat(details.getStartedAt(), is(Optional.of(OffsetDateTime.of(2026, 9, 4, 12, 0, 0, 0, ZoneOffset.UTC))));
        assertThat(
                details.getCompletedAt(), is(Optional.of(OffsetDateTime.of(2026, 9, 4, 12, 5, 30, 0, ZoneOffset.UTC))));
    }

    @Test
    void detailsUrlIsPassedThrough() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withDetailsURL("https://ci.example.com/job/1")
                .build());

        assertThat(details.getDetailsUrl(), is(Optional.of("https://ci.example.com/job/1")));
    }

    @Test
    void blankDetailsUrlIsTreatedAsAbsent() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withDetailsURL("")
                .build());

        assertThat(details.getDetailsUrl(), is(Optional.empty()));
    }

    @Test
    void nonHttpDetailsUrlIsRejected() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withDetailsURL("ftp://example.com/job/1")
                .build());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, details::getDetailsUrl);
        assertThat(e.getMessage(), is("The details url is not http or https scheme: ftp://example.com/job/1"));
    }

    @Test
    void outputIsPassedThrough() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle("Unit tests")
                        .withSummary("128 tests passed.")
                        .withText("All suites green.")
                        .build())
                .build());

        CheckRunOutput output = details.getOutput().orElseThrow();
        assertThat(output.getTitle(), is("Unit tests"));
        assertThat(output.getSummary(), is("128 tests passed."));
        assertThat(output.getText(), is("All suites green."));
    }

    @Test
    void outputTitleIsTruncatedToTheAcceptedLength() {
        String title = "t".repeat(OriginChecksDetails.MAX_TITLE_LENGTH + 100);
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle(title)
                        .withSummary("summary")
                        .build())
                .build());

        String published = details.getOutput().orElseThrow().getTitle();
        assertThat(published.codePointCount(0, published.length()), is(OriginChecksDetails.MAX_TITLE_LENGTH));
        assertThat(published, endsWith(OriginChecksDetails.TRUNCATION_MARKER));
        assertThat(details.getSizeLimitWarnings(), contains(containsString("check output title")));
    }

    /**
     * An oversized annotation is published shortened rather than dropped, or rejected by Origin and so
     * lost along with the rest of its batch. The build log says what was cut.
     */
    @Test
    void oversizedAnnotationFieldsAreTruncatedAndReported() {
        String message = "m".repeat(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES + 500);
        String rawDetails = "r".repeat(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES + 500);
        String title = "t".repeat(OriginChecksDetails.MAX_TITLE_LENGTH + 10);
        OriginChecksDetails details = detailsWith(new ChecksAnnotation.ChecksAnnotationBuilder()
                .withPath("src/Main.java")
                .withStartLine(1)
                .withEndLine(1)
                .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                .withMessage(message)
                .withRawDetails(rawDetails)
                .withTitle(title)
                .build());

        CheckRunAnnotationInput annotation = details.getAnnotations().get(0);
        assertThat(
                annotation.getMessage().getBytes(StandardCharsets.UTF_8).length,
                is(lessThanOrEqualTo(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES)));
        assertThat(annotation.getMessage(), endsWith(OriginChecksDetails.TRUNCATION_MARKER));
        assertThat(
                annotation.getRawDetails().getBytes(StandardCharsets.UTF_8).length,
                is(lessThanOrEqualTo(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES)));
        assertThat(annotation.getRawDetails(), endsWith(OriginChecksDetails.TRUNCATION_MARKER));
        assertThat(annotation.getTitle(), endsWith(OriginChecksDetails.TRUNCATION_MARKER));
        // The annotation is still complete enough to be useful.
        assertThat(annotation.getLocation().getPath(), is("src/Main.java"));

        assertThat(
                details.getSizeLimitWarnings(),
                containsInAnyOrder(
                        containsString("message of 1 annotation(s)"),
                        containsString("rawDetails of 1 annotation(s)"),
                        containsString("title of 1 annotation(s)")));
    }

    /** Cutting mid-character would produce invalid UTF-8, which Origin would reject outright. */
    @Test
    void truncationNeverSplitsAMultiByteCharacter() {
        // Four bytes each, so the limit falls inside a character rather than between two.
        String message = "\uD83D\uDE00".repeat(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES / 3);
        OriginChecksDetails details = detailsWith(new ChecksAnnotation.ChecksAnnotationBuilder()
                .withPath("src/Main.java")
                .withStartLine(1)
                .withEndLine(1)
                .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                .withMessage(message)
                .build());

        String published = details.getAnnotations().get(0).getMessage();
        byte[] bytes = published.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length, is(lessThanOrEqualTo(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES)));
        // A round trip only survives unchanged when every character was kept whole.
        assertThat(new String(bytes, StandardCharsets.UTF_8), is(published));
        assertThat(published, endsWith(OriginChecksDetails.TRUNCATION_MARKER));
    }

    /**
     * A path cannot be shortened without pointing at a file that does not exist, so an over-long one
     * costs the annotation its inline location but not the annotation itself.
     */
    @Test
    void anUnsendablePathCostsTheLocationRatherThanTheAnnotation() {
        String path = "d/".repeat(OriginChecksDetails.MAX_PATH_SIZE_BYTES) + "Main.java";
        OriginChecksDetails details = detailsWith(new ChecksAnnotation.ChecksAnnotationBuilder()
                .withPath(path)
                .withStartLine(7)
                .withEndLine(7)
                .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                .withMessage("still worth reporting")
                .build());

        CheckRunAnnotationInput annotation = details.getAnnotations().get(0);
        assertThat(annotation.getLocation(), is(nullValue()));
        assertThat(annotation.getMessage(), is("still worth reporting"));
        assertThat(
                details.getSizeLimitWarnings(), contains(containsString("1 annotation(s) without a source location")));
    }

    /** A path that fits is kept, so the limit does not cost every annotation its location. */
    @Test
    void aPathWithinTheLimitKeepsItsLocation() {
        OriginChecksDetails details = detailsWith(new ChecksAnnotation.ChecksAnnotationBuilder()
                .withPath("src/main/java/Main.java")
                .withStartLine(7)
                .withEndLine(7)
                .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                .withMessage("message")
                .build());

        assertThat(details.getAnnotations().get(0).getLocation().getPath(), is("src/main/java/Main.java"));
        assertThat(details.getSizeLimitWarnings(), is(empty()));
    }

    @Test
    void nothingIsReportedWhenEverythingFits() {
        OriginChecksDetails details = detailsWith(new ChecksAnnotation.ChecksAnnotationBuilder()
                .withPath("src/Main.java")
                .withStartLine(1)
                .withEndLine(1)
                .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                .withMessage("short")
                .withTitle("also short")
                .build());

        assertThat(details.getAnnotations(), hasSize(1));
        assertThat(details.getSizeLimitWarnings(), is(empty()));
    }

    /** Counting per field must not double up if the annotations are read more than once. */
    @Test
    void truncationIsCountedOncePerField() {
        OriginChecksDetails details = detailsWith(new ChecksAnnotation.ChecksAnnotationBuilder()
                .withPath("src/Main.java")
                .withStartLine(1)
                .withEndLine(1)
                .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                .withMessage("m".repeat(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES + 1))
                .build());

        details.getAnnotations();
        details.getAnnotations();
        details.getOutput();

        assertThat(details.getSizeLimitWarnings(), contains(containsString("message of 1 annotation(s)")));
    }

    private static OriginChecksDetails detailsWith(ChecksAnnotation annotation) {
        return new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withSummary("summary")
                        .withAnnotations(List.of(annotation))
                        .build())
                .build());
    }

    @Test
    void oversizedOutputIsTruncatedToTheAcceptedSize() {
        String summary = ("a".repeat(1000) + "\n").repeat(100);
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle("title")
                        .withSummary(summary)
                        .build())
                .build());

        String published = details.getOutput().orElseThrow().getSummary();
        assertThat(published, is(not(summary)));
        assertThat(
                published.getBytes(StandardCharsets.UTF_8).length,
                is(lessThanOrEqualTo(OriginChecksDetails.MAX_OUTPUT_SIZE_BYTES)));
    }

    @Test
    void checkWithoutOutputHasNoAnnotations() {
        OriginChecksDetails details = new OriginChecksDetails(
                new ChecksDetails.ChecksDetailsBuilder().withName("check").build());

        assertThat(details.getOutput(), is(Optional.empty()));
        assertThat(details.getAnnotations(), is(empty()));
    }

    @Test
    void annotationsAreMapped() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle("title")
                        .withSummary("summary")
                        .addAnnotation(new ChecksAnnotation.ChecksAnnotationBuilder()
                                .withPath("src/main/java/Example.java")
                                .withStartLine(12)
                                .withEndLine(12)
                                .withStartColumn(3)
                                .withEndColumn(9)
                                .withAnnotationLevel(ChecksAnnotation.ChecksAnnotationLevel.WARNING)
                                .withTitle("Unused import")
                                .withMessage("The import is never used")
                                .withRawDetails("checkstyle: UnusedImports")
                                .build())
                        .build())
                .build());

        List<CheckRunAnnotationInput> annotations = details.getAnnotations();
        assertThat(annotations, hasSize(1));
        CheckRunAnnotationInput annotation = annotations.get(0);
        assertThat(annotation.getAnnotationLevel().getValue(), is("warning"));
        assertThat(annotation.getMessage(), is("The import is never used"));
        assertThat(annotation.getTitle(), is("Unused import"));
        assertThat(annotation.getRawDetails(), is("checkstyle: UnusedImports"));
        assertThat(annotation.getLocation().getPath(), is("src/main/java/Example.java"));
        assertThat(annotation.getLocation().getStartLine(), is(12));
        assertThat(annotation.getLocation().getEndLine(), is(12));
        assertThat(annotation.getLocation().getColumns().getStartColumn(), is(3));
        assertThat(annotation.getLocation().getColumns().getEndColumn(), is(9));
    }

    @ParameterizedTest(name = "{0} is reported as {1}")
    @CsvSource({"NONE,notice", "NOTICE,notice", "WARNING,warning", "FAILURE,failure"})
    void annotationLevelIsMapped(ChecksAnnotation.ChecksAnnotationLevel level, String expected) {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .addAnnotation(new ChecksAnnotation.ChecksAnnotationBuilder()
                                .withPath("Example.java")
                                .withLine(1)
                                .withAnnotationLevel(level)
                                .withMessage("message")
                                .build())
                        .build())
                .build());

        assertThat(
                details.getAnnotations().stream()
                        .map(a -> a.getAnnotationLevel().getValue())
                        .toList(),
                contains(expected));
    }

    /** Cursor Origin only accepts a column range that stays within one line. */
    @Test
    void columnsOfAMultiLineAnnotationAreDropped() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .addAnnotation(new ChecksAnnotation.ChecksAnnotationBuilder()
                                .withPath("Example.java")
                                .withStartLine(4)
                                .withEndLine(8)
                                .withStartColumn(1)
                                .withEndColumn(2)
                                .withMessage("message")
                                .build())
                        .build())
                .build());

        assertThat(details.getAnnotations().get(0).getLocation().getColumns(), is(nullValue()));
    }

    /** An annotation without a usable location is still worth reporting, at the run level. */
    @Test
    void annotationWithoutLocationIsReportedWithoutOne() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .addAnnotation(new ChecksAnnotation.ChecksAnnotationBuilder()
                                .withMessage("no location")
                                .build())
                        .build())
                .build());

        assertThat(details.getAnnotations().get(0).getLocation(), is(nullValue()));
    }

    @Test
    void annotationWithoutMessageIsRejected() {
        OriginChecksDetails details = new OriginChecksDetails(new ChecksDetails.ChecksDetailsBuilder()
                .withName("check")
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .addAnnotation(new ChecksAnnotation.ChecksAnnotationBuilder()
                                .withPath("Example.java")
                                .withLine(1)
                                .build())
                        .build())
                .build());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, details::getAnnotations);
        assertThat(e.getMessage(), is("Message of annotation is required but not provided"));
    }
}
