package io.jenkins.plugins.cursor_origin_branch_source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.UnprotectedRootAction;
import hudson.security.csrf.CrumbExclusion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.scm.api.SCMEvent;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

@Extension
public class OriginWebhookEndpoint implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(OriginWebhookEndpoint.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "cursor-origin-webhook";
    }

    @POST
    public HttpResponse doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        byte[] rawBody = req.getInputStream().readAllBytes();

        Map<String, String> headers = new HashMap<>();
        headers.put("webhook-id", req.getHeader("webhook-id"));
        headers.put("webhook-timestamp", req.getHeader("webhook-timestamp"));
        headers.put("webhook-signature", req.getHeader("webhook-signature"));

        if (!OriginWebhookVerifier.verify(rawBody, headers, OriginWebhookVerifier.cachedLiveFetcher())) {
            LOGGER.warning(() -> "Rejected webhook with invalid signature from " + req.getRemoteAddr());
            return HttpResponses.status(401);
        }

        try {
            dispatchWebhook(rawBody, req);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing webhook payload", e);
            return HttpResponses.status(400);
        }
        return HttpResponses.ok();
    }

    @Extension
    public static final class WebhookCrumbExclusion extends CrumbExclusion {
        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
                throws IOException, ServletException {
            String pathInfo = req.getPathInfo();
            if (pathInfo != null && pathInfo.startsWith("/cursor-origin-webhook/")) {
                chain.doFilter(req, resp);
                return true;
            }
            return false;
        }
    }

    private void dispatchWebhook(byte[] rawBody, StaplerRequest2 req) throws IOException {
        JsonNode envelope = MAPPER.readTree(rawBody);
        JsonNode eventNode = envelope.path("event");
        String eventType = eventNode.path("type").asText("");
        JsonNode payload = eventNode.path("payload");
        String appId = envelope.path("appId").asText("");
        String installationId = envelope.path("installationId").asText("");

        LOGGER.fine(() -> "Dispatching webhook event: " + eventType);
        OriginWebhookEvent event = new OriginWebhookEvent(
                eventType, payload, appId, installationId, Instant.now(), SCMEvent.originOf(req));
        for (OriginEventSubscriber subscriber : ExtensionList.lookup(OriginEventSubscriber.class)) {
            try {
                subscriber.onEvent(event);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Subscriber " + subscriber.getClass().getName() + " threw", e);
            }
        }
    }
}
