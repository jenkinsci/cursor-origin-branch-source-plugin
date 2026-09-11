package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.checks.api.ChecksAnnotation;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunAnnotationColumnRange;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunAnnotationInput;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunAnnotationLocation;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunInput;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunOutput;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Translates the SCM neutral {@link ChecksDetails} of the checks API into the Cursor Origin check-run
 * shapes.
 *
 * <p>The translation is not lossless: Origin has no equivalent of the checks API's images or actions,
 * and its output size limits are enforced here rather than by the checks API. Constructing an instance
 * validates the parts of the Origin contract that {@link ChecksDetails} does not enforce itself, so an
 * unpublishable check is rejected before any HTTP request is made.
 */
class OriginChecksDetails {

    /** Maximum UTF-8 size of the {@code summary} and {@code text} output fields. */
    static final int MAX_OUTPUT_SIZE_BYTES = 65_535;

    /** Maximum length of the output and annotation {@code title} fields. */
    static final int MAX_TITLE_LENGTH = 255;

    private final ChecksDetails details;

    OriginChecksDetails(@NonNull ChecksDetails details) {
        if (details.getConclusion() == ChecksConclusion.NONE) {
            if (details.getStatus() == ChecksStatus.COMPLETED) {
                throw new IllegalArgumentException("No conclusion has been set when status is completed.");
            }
            if (details.getCompletedAt().isPresent()) {
                throw new IllegalArgumentException("No conclusion has been set when \"completedAt\" is provided.");
            }
        }
        this.details = details;
    }

    /**
     * The check-run name, which doubles as its key: Origin matches repeated reports on the key, so
     * using the checks API name keeps a subsequent report of the same check an update rather than a
     * duplicate.
     */
    @NonNull
    String getName() {
        return details.getName()
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("The check name is blank."));
    }

    @NonNull
    CheckRunInput.StatusEnum getStatus() {
        return switch (details.getStatus()) {
            case NONE, QUEUED -> CheckRunInput.StatusEnum.QUEUED;
            case IN_PROGRESS -> CheckRunInput.StatusEnum.IN_PROGRESS;
            case COMPLETED -> CheckRunInput.StatusEnum.COMPLETED;
        };
    }

    /**
     * The conclusion, which Origin requires exactly when the status is {@code completed} and rejects
     * otherwise.
     */
    Optional<CheckRunInput.ConclusionEnum> getConclusion() {
        if (details.getStatus() != ChecksStatus.COMPLETED) {
            return Optional.empty();
        }
        return switch (details.getConclusion()) {
            case SUCCESS -> Optional.of(CheckRunInput.ConclusionEnum.SUCCESS);
            case FAILURE -> Optional.of(CheckRunInput.ConclusionEnum.FAILURE);
            case NEUTRAL -> Optional.of(CheckRunInput.ConclusionEnum.NEUTRAL);
            case CANCELED -> Optional.of(CheckRunInput.ConclusionEnum.CANCELLED);
            case TIME_OUT -> Optional.of(CheckRunInput.ConclusionEnum.TIMED_OUT);
            case SKIPPED -> Optional.of(CheckRunInput.ConclusionEnum.SKIPPED);
            case ACTION_REQUIRED -> Optional.of(CheckRunInput.ConclusionEnum.ACTION_REQUIRED);
            case NONE -> throw new IllegalArgumentException("No conclusion has been set when status is completed.");
        };
    }

    Optional<OffsetDateTime> getStartedAt() {
        return details.getStartedAt().map(OriginChecksDetails::toOffsetDateTime);
    }

    Optional<OffsetDateTime> getCompletedAt() {
        return details.getCompletedAt().map(OriginChecksDetails::toOffsetDateTime);
    }

    /** The consumer supplied details URL, if it is usable as a link. */
    Optional<String> getDetailsUrl() {
        return details.getDetailsURL().filter(url -> !url.isBlank()).map(url -> {
            String scheme;
            try {
                scheme = new URI(url).getScheme();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("The details url is not a valid URI: " + url, e);
            }
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("The details url is not http or https scheme: " + url);
            }
            return url;
        });
    }

    /** The human readable output, truncated to the sizes Origin accepts. */
    Optional<CheckRunOutput> getOutput() {
        return details.getOutput().map(output -> {
            CheckRunOutput origin = new CheckRunOutput();
            output.getTitle().map(OriginChecksDetails::truncateTitle).ifPresent(origin::title);
            output.getSummary(MAX_OUTPUT_SIZE_BYTES).ifPresent(origin::summary);
            output.getText(MAX_OUTPUT_SIZE_BYTES).ifPresent(origin::text);
            return origin;
        });
    }

    /**
     * The annotations to append to the check run. Origin models these as a separate sub-resource, so
     * unlike the output they are not part of the check-run upsert itself.
     */
    @NonNull
    List<CheckRunAnnotationInput> getAnnotations() {
        return details.getOutput().map(ChecksOutput::getChecksAnnotations).orElse(List.of()).stream()
                .map(OriginChecksDetails::toAnnotation)
                .toList();
    }

    private static CheckRunAnnotationInput toAnnotation(ChecksAnnotation annotation) {
        CheckRunAnnotationInput input = new CheckRunAnnotationInput()
                .annotationLevel(toAnnotationLevel(annotation.getAnnotationLevel()))
                .message(annotation
                        .getMessage()
                        .orElseThrow(() ->
                                new IllegalArgumentException("Message of annotation is required but not provided")));
        annotation.getTitle().map(OriginChecksDetails::truncateTitle).ifPresent(input::title);
        annotation.getRawDetails().ifPresent(input::rawDetails);
        toLocation(annotation).ifPresent(input::location);
        return input;
    }

    /**
     * Builds the source location of an annotation. Origin requires a path and a coherent line range,
     * so an annotation missing either is reported at the run level instead of inline.
     */
    private static Optional<CheckRunAnnotationLocation> toLocation(ChecksAnnotation annotation) {
        Optional<String> path = annotation.getPath().filter(p -> !p.isBlank());
        Optional<Integer> startLine = annotation.getStartLine();
        Optional<Integer> endLine = annotation.getEndLine();
        if (path.isEmpty() || startLine.isEmpty() || endLine.isEmpty()) {
            return Optional.empty();
        }
        CheckRunAnnotationLocation location = new CheckRunAnnotationLocation()
                .path(path.get())
                .startLine(startLine.get())
                .endLine(endLine.get());
        // Origin only accepts a column range on a single line annotation.
        if (startLine.get().equals(endLine.get())
                && annotation.getStartColumn().isPresent()
                && annotation.getEndColumn().isPresent()) {
            location.columns(new CheckRunAnnotationColumnRange()
                    .startColumn(annotation.getStartColumn().get())
                    .endColumn(annotation.getEndColumn().get()));
        }
        return Optional.of(location);
    }

    private static CheckRunAnnotationInput.AnnotationLevelEnum toAnnotationLevel(
            ChecksAnnotation.ChecksAnnotationLevel level) {
        return switch (level) {
            case NOTICE, NONE -> CheckRunAnnotationInput.AnnotationLevelEnum.NOTICE;
            case WARNING -> CheckRunAnnotationInput.AnnotationLevelEnum.WARNING;
            case FAILURE -> CheckRunAnnotationInput.AnnotationLevelEnum.FAILURE;
        };
    }

    private static String truncateTitle(String title) {
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH);
    }

    /** The checks API models its timestamps as UTC local times. */
    private static OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime.atOffset(ZoneOffset.UTC);
    }

    @Override
    public String toString() {
        return details.toString();
    }
}
