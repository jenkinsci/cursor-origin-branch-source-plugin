package io.jenkins.plugins.cursor_origin_branch_source.checks;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import io.jenkins.plugins.cursor_origin_branch_source.OriginEventSubscriber;
import io.jenkins.plugins.cursor_origin_branch_source.OriginWebhookEvent;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;

/**
 * Handles check-run webhook events from Cursor Origin.
 *
 * <p>When Origin fires {@code repository.check_run.rerequested} it means a user clicked "Re-run"
 * on a check run. The {@code checkRun.externalId} in the payload is the Jenkins run's
 * externalizable ID (set by {@link OriginChecksPublisher}), which lets us locate the original
 * build and schedule a fresh one for the same job.
 */
@Extension
public class OriginChecksEventSubscriber implements OriginEventSubscriber {

    private static final Logger LOGGER = Logger.getLogger(OriginChecksEventSubscriber.class.getName());

    @Override
    public void onEvent(@NonNull OriginWebhookEvent event) {
        if (!"repository.check_run.rerequested".equals(event.eventType())) {
            return;
        }
        JsonNode payload = event.payload();
        String externalId = payload.path("checkRun").path("externalId").asText("");
        if (externalId.isEmpty()) {
            LOGGER.fine("check_run.rerequested event has no checkRun.externalId; ignoring");
            return;
        }
        Run<?, ?> run = Run.fromExternalizableId(externalId);
        if (run == null) {
            LOGGER.fine(() -> "check_run.rerequested: no run found for externalId " + externalId);
            return;
        }
        if (!(run.getParent() instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> job)) {
            LOGGER.fine(() -> "check_run.rerequested: job " + run.getParent().getFullName()
                    + " is not schedulable; ignoring");
            return;
        }
        LOGGER.fine(() -> "check_run.rerequested: scheduling rebuild of " + run.getFullDisplayName());
        job.scheduleBuild2(0);
    }
}
