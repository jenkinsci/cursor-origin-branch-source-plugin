package io.jenkins.plugins.cursor_origin_branch_source;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Carries a verified Cursor Origin webhook delivery to {@link OriginEventSubscriber} instances. */
public final class OriginWebhookEvent {

    private final String eventType;
    private final JsonNode payload;
    private final String appId;
    private final String installationId;
    private final long timestampMs;
    private final String origin;

    OriginWebhookEvent(
            @NonNull String eventType,
            @NonNull JsonNode payload,
            @NonNull String appId,
            @NonNull String installationId,
            long timestampMs,
            @NonNull String origin) {
        this.eventType = eventType;
        this.payload = payload;
        this.appId = appId;
        this.installationId = installationId;
        this.timestampMs = timestampMs;
        this.origin = origin;
    }

    /** Event type slug, e.g. {@code "repository.pushed"} or {@code "pull_request.created"}. */
    @NonNull
    public String getEventType() {
        return eventType;
    }

    /** Raw event payload node from the webhook envelope's {@code event.payload} field. */
    @NonNull
    public JsonNode getPayload() {
        return payload;
    }

    @NonNull
    public String getAppId() {
        return appId;
    }

    @NonNull
    public String getInstallationId() {
        return installationId;
    }

    /** Event time in milliseconds since epoch (derived from system clock at receipt). */
    public long getTimestampMs() {
        return timestampMs;
    }

    /** Origin string identifying this webhook endpoint, e.g. {@code "cursor-origin-webhook"}. */
    @NonNull
    public String getOrigin() {
        return origin;
    }
}
