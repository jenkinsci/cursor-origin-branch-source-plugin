package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.CheckForNull;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Maximum UTF-8 size of the {@code summary} and {@code text} output fields, and of an annotation's
     * {@code message} and {@code rawDetails}.
     */
    static final int MAX_OUTPUT_SIZE_BYTES = 65_535;

    /** Maximum length of the output and annotation {@code title} fields. */
    static final int MAX_TITLE_LENGTH = 255;

    /** Appended to a value that had to be shortened, so the reader knows something is missing. */
    static final String TRUNCATION_MARKER = "…[truncated]";

    private final ChecksDetails details;

    /** Built once, so that the truncations it records are counted once. */
    @CheckForNull
    private List<CheckRunAnnotationInput> annotations;

    /** How many annotations had each field shortened, keyed by the Origin field name. */
    private final Map<String, Integer> truncatedAnnotationFields = new LinkedHashMap<>();

    private boolean outputTitleTruncated;

    /** Built once, so that the truncations it records are counted once. */
    @CheckForNull
    private CheckRunOutput output;

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
        if (output == null) {
            output = details.getOutput()
                    .map(source -> {
                        CheckRunOutput origin = new CheckRunOutput();
                        source.getTitle()
                                .map(title -> truncateTitle(title, null))
                                .ifPresent(origin::title);
                        source.getSummary(MAX_OUTPUT_SIZE_BYTES).ifPresent(origin::summary);
                        source.getText(MAX_OUTPUT_SIZE_BYTES).ifPresent(origin::text);
                        return origin;
                    })
                    .orElse(null);
        }
        return Optional.ofNullable(output);
    }

    /**
     * The annotations to append to the check run. Origin models these as a separate sub-resource, so
     * unlike the output they are not part of the check-run upsert itself.
     */
    @NonNull
    List<CheckRunAnnotationInput> getAnnotations() {
        if (annotations == null) {
            annotations = details.getOutput().map(ChecksOutput::getChecksAnnotations).orElse(List.of()).stream()
                    .map(this::toAnnotation)
                    .toList();
        }
        return annotations;
    }

    /**
     * Describes any values that had to be shortened to be publishable, one line per field, so that the
     * caller can say so in the build log. Empty when everything fitted.
     *
     * <p>Only meaningful once {@link #getAnnotations()} has been called.
     */
    @NonNull
    List<String> getTruncationWarnings() {
        getOutput();
        getAnnotations();
        List<String> warnings = new ArrayList<>();
        if (outputTitleTruncated) {
            warnings.add(String.format(
                    "Truncated the check output title to Origin's limit of %d characters.", MAX_TITLE_LENGTH));
        }
        truncatedAnnotationFields.forEach((field, count) -> warnings.add(String.format(
                "Truncated the %s of %d annotation(s) to Origin's limit; the full text remains in the Jenkins build.",
                field, count)));
        return List.copyOf(warnings);
    }

    private CheckRunAnnotationInput toAnnotation(ChecksAnnotation annotation) {
        CheckRunAnnotationInput input = new CheckRunAnnotationInput()
                .annotationLevel(toAnnotationLevel(annotation.getAnnotationLevel()))
                .message(truncateUtf8(
                        annotation
                                .getMessage()
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Message of annotation is required but not provided")),
                        "message"));
        annotation.getTitle().map(title -> truncateTitle(title, "title")).ifPresent(input::title);
        annotation.getRawDetails().map(raw -> truncateUtf8(raw, "rawDetails")).ifPresent(input::rawDetails);
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

    /**
     * Truncates to Origin's title limit, which it states in Unicode characters rather than UTF-16
     * units, so this counts code points and never splits a surrogate pair.
     *
     * @param field the annotation field being truncated, or {@code null} for the output title
     */
    private String truncateTitle(String title, @CheckForNull String field) {
        if (title.codePointCount(0, title.length()) <= MAX_TITLE_LENGTH) {
            return title;
        }
        record(field);
        int budget = MAX_TITLE_LENGTH - TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length());
        int end = title.offsetByCodePoints(0, Math.max(budget, 0));
        return title.substring(0, end) + TRUNCATION_MARKER;
    }

    /** Truncates to Origin's UTF-8 byte limit, keeping whole code points so the result stays valid. */
    private String truncateUtf8(String value, String field) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= MAX_OUTPUT_SIZE_BYTES) {
            return value;
        }
        record(field);
        int budget = MAX_OUTPUT_SIZE_BYTES - TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;
        return keepFirstUtf8Bytes(value, Math.max(budget, 0)) + TRUNCATION_MARKER;
    }

    /** Keeps the longest prefix of whole code points whose UTF-8 encoding fits in {@code maxBytes}. */
    private static String keepFirstUtf8Bytes(String value, int maxBytes) {
        int used = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = utf8Width(codePoint);
            if (used + width > maxBytes) {
                break;
            }
            used += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    private void record(@CheckForNull String field) {
        if (field == null) {
            outputTitleTruncated = true;
        } else {
            truncatedAnnotationFields.merge(field, 1, Integer::sum);
        }
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
