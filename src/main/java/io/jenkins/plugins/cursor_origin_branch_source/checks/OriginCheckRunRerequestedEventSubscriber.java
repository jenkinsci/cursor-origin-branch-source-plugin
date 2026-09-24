package io.jenkins.plugins.cursor_origin_branch_source.checks;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Run;
import io.jenkins.plugins.cursor_origin_branch_source.OriginEventSubscriber;
import io.jenkins.plugins.cursor_origin_branch_source.OriginWebhookEvent;
import java.util.logging.Logger;

/**
 * Handles check-run webhook events from Cursor Origin.
 *
 * <p>When Origin fires {@code repository.check_run.rerequested} it means a user clicked "Re-run"
 * on a check run. The {@code checkRun.externalId} in the payload is the Jenkins run's
 * externalizable ID (set by {@link OriginChecksPublisher}), which lets us locate the original
 * build and delegate scheduling to the first {@link OriginCheckRerunHandler} that claims it.
 *
 * <p>Although this subscriber no longer references workflow-cps directly, it is guarded as optional
 * because there are currently no non-workflow-cps {@link OriginCheckRerunHandler} implementations;
 * without that guard an instance would be registered for every webhook delivery even when no handler
 * could act on it.
 */
@Extension
public class OriginCheckRunRerequestedEventSubscriber implements OriginEventSubscriber {

    private static final Logger LOGGER = Logger.getLogger(OriginCheckRunRerequestedEventSubscriber.class.getName());

    private OriginSCMFacade originSCMFacade = new OriginSCMFacade();

    @Override
    public void onEvent(@NonNull OriginWebhookEvent event) {
        if (!"repository.check_run.rerequested".equals(event.eventType())) {
            return;
        }
        JsonNode payload = event.payload();
        String externalId = payload.path("checkRun").path("externalId").asText("");
        if (externalId.isEmpty()) {
            LOGGER.warning(
                    "check_run.rerequested event has no checkRun.externalId; ignoring (enable FINE logging to see more information about the event)");
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
            LOGGER.info(() -> "check_run.rerequested: project is not buildable for " + externalId);
            return;
        }
        if (originSCMFacade.findOriginSCMSource(run.getParent()).isEmpty()) {
            LOGGER.info(() -> "check_run.rerequested: project is not using OriginSCM for " + externalId);
            return;
        }

        OriginCheckRerunCause cause = createCauseFromPayload(run, payload);
        boolean handled = OriginCheckRerunHandler.rerun(run, cause);
        if (handled) {
            LOGGER.info(() -> "check_run.rerequested: scheduled rerun of " + externalId);
        } else {
            LOGGER.info(() -> "check_run.rerequested: no rerun handler available for " + externalId);
        }
    }

    static OriginCheckRerunCause createCauseFromPayload(Run<?, ?> rebuildOf, JsonNode payload) {
        JsonNode rerequestor = payload.path("checkRun").path("rerequestedBy");
        if (!rerequestor.path("user").isMissingNode()) {
            JsonNode user = rerequestor.path("user");
            String id = user.path("id").asText(null);
            String email = user.path("email").asText();
            String displayName = user.path("displayName").asText(null);
            return new OriginCheckRerunCause.OriginCheckRerunUserCause(
                    rebuildOf, Util.fixEmpty(id), email, Util.fixEmpty(displayName));
        }
        if (!rerequestor.path("app").isMissingNode()) {
            JsonNode app = rerequestor.path("app");
            String id = app.path("id").asText(null);
            String displayName = app.path("displayName").asText(null);
            // extract the owner where the app is installed from the repo that generated this hook
            String namespace =
                    payload.path("repository").path("owner").path("slug").asText();
            return new OriginCheckRerunCause.OriginCheckRerunAppCause(
                    rebuildOf, namespace, id, Util.fixEmpty(displayName));
        }
        if (!rerequestor.path("serviceAccount").isMissingNode()) {
            JsonNode serviceAccount = rerequestor.path("serviceAccount");
            String id = serviceAccount.path("id").asText(null);
            return new OriginCheckRerunCause.OriginCheckRerunServiceAccountCause(rebuildOf, id);
        }
        // unknown use fallback
        return new OriginCheckRerunCause(rebuildOf);
    }
}
