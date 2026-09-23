package io.jenkins.plugins.cursor_origin_branch_source.checks;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.CauseAction;
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
        Queue.Item qi = action.run2(action.getOriginalScript(), action.getOriginalLoadedScripts(), true);
        if (qi == null) {
            LOGGER.info(() -> "check_run.rerequested: project is not buildable" + externalId);
            return;
        }
        // record that this was triggered by an origin check rerun
        OriginCheckRerunCause orcc = createCauseFromPayload(payload);
        CauseAction tmpCauseAction = new CauseAction(orcc);
        tmpCauseAction.foldIntoExisting(qi, qi.task, Collections.emptyList());
        LOGGER.info(() -> "check_run.rerequested: scheduled rebuild of " + externalId);
    }

    static OriginCheckRerunCause createCauseFromPayload(JsonNode payload) {
        JsonNode rerequestor = payload.path("checkRun").path("rerequestedBy");
        if (!rerequestor.path("user").isMissingNode()) {
            JsonNode user = rerequestor.path("user");
            String id = user.path("id").asText(null);
            String email = user.path("email").asText();
            String displayName = user.path("displayName").asText(null);
            return new OriginCheckRerunCause.OriginCheckRerunUserCause(
                    Util.fixEmpty(id), email, Util.fixEmpty(displayName));
        }
        if (!rerequestor.path("app").isMissingNode()) {
            JsonNode app = rerequestor.path("app");
            String id = app.path("id").asText(null);
            String displayName = app.path("displayName").asText(null);
            // extract the owner where the app is installed from the repo that generated this hook
            String namespace =
                    payload.path("repository").path("owner").path("slug").asText();
            return new OriginCheckRerunCause.OriginCheckRerunAppCause(namespace, id, Util.fixEmpty(displayName));
        }
        if (!rerequestor.path("serviceAccount").isMissingNode()) {
            JsonNode serviceAccount = rerequestor.path("serviceAccount");
            String id = serviceAccount.path("id").asText(null);
            return new OriginCheckRerunCause.OriginCheckRerunServiceAccountCause(id);
        }
        // unknown use fallback
        return new OriginCheckRerunCause();
    }
}
