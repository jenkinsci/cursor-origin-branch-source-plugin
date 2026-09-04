package io.jenkins.plugins.cursor_origin_branch_source;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Carries a verified Cursor Origin webhook delivery to {@link OriginEventSubscriber} instances.
 *
 * @param eventType      event type slug, e.g. {@code "repository.pushed"} or {@code "pull_request.created"}
 * @param payload        raw event payload node from the webhook envelope's {@code event.payload} field
 * @param appId          app ID from the delivery envelope
 * @param installationId installation ID from the delivery envelope
 * @param timestamp      time of receipt on this controller
 * @param origin         string identifying the source, e.g. {@code "cursor-origin-webhook"}
 */
public record OriginWebhookEvent(
        @NonNull String eventType,
        @NonNull JsonNode payload,
        @NonNull String appId,
        @NonNull String installationId,
        @NonNull Instant timestamp,
        @NonNull String origin) {}
