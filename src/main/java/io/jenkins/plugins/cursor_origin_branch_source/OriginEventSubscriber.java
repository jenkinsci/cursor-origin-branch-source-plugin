package io.jenkins.plugins.cursor_origin_branch_source;

import hudson.ExtensionPoint;

/**
 * Extension point for receiving verified Cursor Origin webhook events.
 *
 * <p>Implement this interface and annotate with {@link hudson.Extension} to receive
 * {@link OriginWebhookEvent}s dispatched by {@link OriginWebhookEndpoint}.
 * Implementations must return quickly from {@link #onEvent}; defer expensive work to a
 * background thread.
 */
public interface OriginEventSubscriber extends ExtensionPoint {

    void onEvent(OriginWebhookEvent event);
}
