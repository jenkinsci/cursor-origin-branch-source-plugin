package io.jenkins.plugins.cursor_origin_branch_source.checks;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Queue;
import hudson.model.Run;
import io.jenkins.plugins.cursor_origin_branch_source.OriginEventSubscriber;
import io.jenkins.plugins.cursor_origin_branch_source.OriginWebhookEvent;
import java.util.Collections;
import java.util.logging.Logger;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;

/**
 * Handles check-run webhook events from Cursor Origin.
 *
 * <p>When Origin fires {@code repository.check_run.rerequested} it means a user clicked "Re-run"
 * on a check run. The {@code checkRun.externalId} in the payload is the Jenkins run's
 * externalizable ID (set by {@link OriginChecksPublisher}), which lets us locate the original
 * build and schedule a fresh one for the same job.
 */
@OptionalExtension(requirePlugins = "workflow-cps")
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
            LOGGER.warning(
                    "check_run.rerequested event has no checkRun.externalId; ignoring (enbable FINE logging to see more information about the event)");
            LOGGER.fine(() ->
                    "check_run.rerequested event payload -> " + event.payload().toPrettyString());
            return;
        }
        Run<?, ?> run = Run.fromExternalizableId(externalId);
        if (run == null) {
            LOGGER.info(() -> "check_run.rerequested: no run found for externalId " + externalId);
            return;
        }
        if (!run.getParent().isBuildable()) {
            LOGGER.info(() -> "check_run.rerequested: project is not buildable" + externalId);
            return;
        }
        ReplayAction action = run.getAction(ReplayAction.class);
        if (action == null) {
            LOGGER.info(() -> "check_run.rerequested: run is not rebuildable for externalId " + externalId);
            return;
        }
        Queue.Item task = action.run2(action.getOriginalScript(), action.getOriginalLoadedScripts(), true);

        if (task == null) {
            LOGGER.info(() -> "check_run.rerequested: project is not buildable" + externalId);
            return;
        }
        LOGGER.info(() -> "check_run.rerequested: scheduled rebuild of " + externalId);
    }
}
