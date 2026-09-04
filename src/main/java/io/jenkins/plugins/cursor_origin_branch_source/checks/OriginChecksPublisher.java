package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiException;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunAnnotationInput;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckRunInput;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.CheckSuiteInput;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.OriginServiceCreateCheckRunAnnotationsRequest;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.OriginServicePostCheckRunRequest;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.PostCheckRunResponse;
import io.jenkins.plugins.util.PluginLogger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes checks to Cursor Origin.
 *
 * <p>Origin upserts a check run on {@code (repository, head SHA, suite key, check key)}, so each
 * publish is a single self-contained request: there is no create-then-update distinction and no need
 * to remember a server side id between the {@code queued}, {@code in_progress} and {@code completed}
 * reports of the same check.
 */
class OriginChecksPublisher extends ChecksPublisher {

    private static final Logger SYSTEM_LOGGER = Logger.getLogger(OriginChecksPublisher.class.getName());

    /** Origin accepts at most this many annotations per request. */
    static final int ANNOTATION_BATCH_SIZE = 25;

    /** Origin stores at most this many annotations per check run. */
    static final int MAX_ANNOTATIONS_PER_CHECK_RUN = 100;

    private final OriginChecksContext context;
    private final PluginLogger buildLogger;
    private final ApiFactory apiFactory;

    /** Creates an Origin API client for a context; a seam so that tests can stub the API. */
    @FunctionalInterface
    interface ApiFactory {
        OriginServiceApi create(OriginChecksContext context);
    }

    OriginChecksPublisher(@NonNull OriginChecksContext context, @NonNull PluginLogger buildLogger) {
        this(context, buildLogger, OriginChecksContext::createApi);
    }

    OriginChecksPublisher(
            @NonNull OriginChecksContext context, @NonNull PluginLogger buildLogger, @NonNull ApiFactory apiFactory) {
        this.context = context;
        this.buildLogger = buildLogger;
        this.apiFactory = apiFactory;
    }

    @Override
    public void publish(final ChecksDetails details) {
        try {
            OriginChecksDetails originDetails = new OriginChecksDetails(details);
            OriginServiceApi api = apiFactory.create(context);

            PostCheckRunResponse response = api.originServicePostCheckRun(
                    context.getRepoOwner(),
                    context.getRepository(),
                    new OriginServicePostCheckRunRequest()
                            .headSha(context.getHeadSha())
                            .checkSuite(createSuite())
                            .checkRun(createRun(originDetails)));

            publishAnnotations(api, response, originDetails);
            logUnsupportedParts(details);

            buildLogger.log(
                    "Cursor Origin check (name: %s, status: %s) has been published.",
                    originDetails.getName(), originDetails.getStatus().getValue());
            SYSTEM_LOGGER.fine(() -> sanitize(String.format(
                    "Published check for repo: %s/%s, sha: %s, job: %s, name: %s, status: %s",
                    context.getRepoOwner(),
                    context.getRepository(),
                    context.getHeadSha(),
                    context.getJob().getFullName(),
                    originDetails.getName(),
                    originDetails.getStatus().getValue())));
        } catch (ApiException | RuntimeException e) {
            // Also covers token minting, credential lookup and contract violations in the details.
            logFailure(details, e);
        }
    }

    /** Cursor Origin has no equivalent of the checks API's actions or images, so those are dropped. */
    private void logUnsupportedParts(ChecksDetails details) {
        if (!details.getActions().isEmpty()) {
            SYSTEM_LOGGER.fine(() -> String.format(
                    "Cursor Origin does not support check actions; dropped %d of them.",
                    details.getActions().size()));
        }
        details.getOutput()
                .map(ChecksOutput::getChecksImages)
                .filter(images -> !images.isEmpty())
                .ifPresent(images -> SYSTEM_LOGGER.fine(() -> String.format(
                        "Cursor Origin does not support check images; dropped %d of them.", images.size())));
    }

    private CheckSuiteInput createSuite() {
        return new CheckSuiteInput()
                .key(context.getSuiteKey())
                .name(context.getSuiteName())
                .externalId(context.getExternalId())
                .detailsUrl(context.getUrl());
    }

    private CheckRunInput createRun(OriginChecksDetails details) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CheckRunInput run = new CheckRunInput()
                .key(details.getName())
                .name(details.getName())
                .status(details.getStatus())
                // Orders concurrent updates, so that a stale retry cannot overwrite newer state.
                .externalUpdatedAt(now)
                .externalId(context.getExternalId())
                .detailsUrl(details.getDetailsUrl().orElseGet(context::getUrl));

        details.getStartedAt().ifPresent(run::startedAt);
        details.getOutput().ifPresent(run::output);
        details.getConclusion()
                .ifPresent(conclusion -> run.conclusion(conclusion)
                        .completedAt(details.getCompletedAt().orElse(now)));
        return run;
    }

    /**
     * Appends any annotations Origin has not been sent yet, in the batches it accepts.
     *
     * <p>A failure here is reported but not propagated: the check run itself has already been
     * published successfully at this point, and losing its annotations should not look like a failure
     * to report the check at all.
     */
    private void publishAnnotations(OriginServiceApi api, PostCheckRunResponse response, OriginChecksDetails details) {
        List<CheckRunAnnotationInput> annotations = details.getAnnotations();
        if (annotations.isEmpty()) {
            return;
        }
        String checkRunId =
                response.getCheckRun() == null ? null : response.getCheckRun().getId();
        if (checkRunId == null || checkRunId.isBlank()) {
            buildLogger.log(
                    "Cursor Origin did not return a check-run id; skipping %d annotations.", annotations.size());
            return;
        }

        OriginChecksAction action = getOrCreateAction(details.getName(), checkRunId);
        int alreadyPublished = action == null ? 0 : action.getPublishedAnnotations();
        if (alreadyPublished >= annotations.size()) {
            return;
        }
        List<CheckRunAnnotationInput> pending = annotations.subList(alreadyPublished, annotations.size());
        int capacity = MAX_ANNOTATIONS_PER_CHECK_RUN - alreadyPublished;
        if (pending.size() > capacity) {
            buildLogger.log(
                    "Cursor Origin stores at most %d annotations per check run; dropping %d of %d annotations of check '%s'.",
                    MAX_ANNOTATIONS_PER_CHECK_RUN, pending.size() - capacity, annotations.size(), details.getName());
            pending = pending.subList(0, Math.max(capacity, 0));
        }

        for (int from = 0; from < pending.size(); from += ANNOTATION_BATCH_SIZE) {
            List<CheckRunAnnotationInput> batch =
                    pending.subList(from, Math.min(from + ANNOTATION_BATCH_SIZE, pending.size()));
            try {
                api.originServiceCreateCheckRunAnnotations(
                        context.getRepoOwner(),
                        context.getRepository(),
                        checkRunId,
                        new OriginServiceCreateCheckRunAnnotationsRequest().annotations(List.copyOf(batch)));
            } catch (ApiException e) {
                String message = String.format(
                        "Failed publishing %d annotations of Cursor Origin check '%s': ",
                        batch.size(), details.getName());
                SYSTEM_LOGGER.log(Level.WARNING, sanitize(message), e);
                buildLogger.log("%s", message + e.getMessage());
                return;
            }
            if (action != null) {
                action.addPublishedAnnotations(batch.size());
            }
        }
    }

    /**
     * Returns the record of what has already been published for this check, creating it if this is the
     * first publish. Returns {@code null} for a queued check, which has no build to record against.
     */
    private OriginChecksAction getOrCreateAction(String checkKey, String checkRunId) {
        Optional<Run<?, ?>> run = context.getRun();
        if (run.isEmpty()) {
            return null;
        }
        Optional<OriginChecksAction> existing = run.get().getActions(OriginChecksAction.class).stream()
                .filter(action -> action.getCheckKey().equals(checkKey))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        OriginChecksAction action = new OriginChecksAction(checkKey, checkRunId);
        run.get().addAction(action);
        return action;
    }

    private void logFailure(ChecksDetails details, Exception e) {
        String message = "Failed publishing Cursor Origin checks: ";
        SYSTEM_LOGGER.log(Level.WARNING, sanitize(message + details), e);
        buildLogger.log("%s", message + e);
    }

    /** Strips line breaks so that external data cannot forge log entries. */
    private static String sanitize(String message) {
        return message.replaceAll("[\r\n]", "");
    }
}
