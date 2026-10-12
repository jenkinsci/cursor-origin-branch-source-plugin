package io.jenkins.plugins.cursor_origin_branch_source.checks;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Run;
import io.jenkins.plugins.cursor_origin_branch_source.OriginEventSubscriber;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import io.jenkins.plugins.cursor_origin_branch_source.OriginWebhookEvent;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Handles check-run webhook events from Cursor Origin.
 *
 * <p>When Origin fires {@code repository.check_run.rerequested} it means a user clicked "Re-run"
 * on a check run. The {@code checkRun.externalId} in the payload is the Jenkins run's
 * externalizable ID (set by {@link OriginChecksPublisher}), which lets us locate the original
 * build and delegate scheduling to the first {@link OriginCheckRerunHandler} that claims it.
 *
 * <p>A verified signature only establishes that Cursor Origin sent the delivery, not that the
 * {@code externalId} it carries belongs to the repository the event names. Since anyone able to
 * create a check run can choose its {@code externalId}, the run this resolves to is only rerun once
 * its {@link OriginSCMSource} is confirmed to track the repository the event was fired for.
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
        Optional<OriginSCMSource> source = originSCMFacade.findOriginSCMSource(run.getParent());
        if (source.isEmpty()) {
            LOGGER.info(() -> "check_run.rerequested: project is not using OriginSCM for " + externalId);
            return;
        }
        // The externalId alone selects the run, so without tying it to the repository the event was
        // fired for, a check run in one repository could rerun a job built from an unrelated one.
        JsonNode repository = payload.path("repository");
        String eventOwner = repository.path("owner").path("slug").asText("");
        String eventRepository = repository.path("name").asText("");
        if (!eventOwner.equals(source.get().getRepoOwner())
                || !eventRepository.equals(source.get().getRepository())) {
            LOGGER.warning(() -> "check_run.rerequested: event repository " + eventOwner + "/" + eventRepository
                    + " does not back " + externalId + "; ignoring");
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
