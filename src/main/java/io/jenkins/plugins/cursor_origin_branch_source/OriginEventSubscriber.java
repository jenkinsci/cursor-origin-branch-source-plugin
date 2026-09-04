package io.jenkins.plugins.cursor_origin_branch_source;

import hudson.ExtensionPoint;

/**
 * Extension point for receiving verified Cursor Origin webhook events.
 *
 * <p>Implement this and annotate with {@link hudson.Extension} to receive
 * {@link OriginWebhookEvent}s dispatched by {@link OriginWebhookEndpoint}.
 */
public abstract class OriginEventSubscriber implements ExtensionPoint {

    /**
     * Called for each verified webhook delivery on {@link OriginWebhookEndpoint}.
     * Implementations must return quickly; defer expensive work to a background thread.
     *
     * @param event the verified webhook event
     */
    public abstract void onEvent(OriginWebhookEvent event);
}
