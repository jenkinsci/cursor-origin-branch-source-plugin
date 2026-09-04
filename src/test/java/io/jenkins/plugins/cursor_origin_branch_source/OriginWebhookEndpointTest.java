package io.jenkins.plugins.cursor_origin_branch_source;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import jenkins.branch.OrganizationFolder;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end tests for {@link OriginWebhookEndpoint}: webhook delivery → SCM event → job change. */
class OriginWebhookEndpointTest extends MockOriginServerTestBase {

    private static final String JENKINSFILE = "pipeline { agent any; stages { stage('S') { steps { echo 'hi' } } } }";

    private String webhookUrl;

    @BeforeEach
    void setWebhookUrl() throws Exception {
        webhookUrl = r.getURL().toExternalForm() + "cursor-origin-webhook/";
        // Clear verifier cache so each test fetches the fresh mock JWKS
        OriginWebhookVerifier.clearCache();
    }

    @Test
    void pushToBranchTriggersIndexing() throws Exception {
        mockServer.addRepo(OWNER, "myrepo", "main").branch("main", "aaaa1111").file("Jenkinsfile", JENKINSFILE);

        OrganizationFolder folder = createOrgFolder();
        r.waitUntilNoActivity();

        WorkflowMultiBranchProject mb = (WorkflowMultiBranchProject) folder.getItem("myrepo");
        assertNotNull(mb);
        assertThat(mb.getItem("main"), notNullValue());

        // Replace the repo with updated state (adds feature branch)
        mockServer
                .replaceRepo(OWNER, "myrepo", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .branch("feature", "bbbb2222")
                .file("Jenkinsfile", JENKINSFILE);

        // Deliver push webhook for the new branch
        mockServer.deliverWebhook(webhookUrl, APP_ID, INSTALLATION_ID, "repository.pushed", gen -> {
            gen.writeStartObject();
            gen.writeObjectFieldStart("repository");
            gen.writeObjectFieldStart("owner");
            gen.writeStringField("slug", OWNER);
            gen.writeEndObject();
            gen.writeStringField("name", "myrepo");
            gen.writeEndObject();
            gen.writeArrayFieldStart("refUpdates");
            gen.writeStartObject();
            gen.writeStringField("ref", "refs/heads/feature");
            gen.writeStringField("before", "0000000000000000000000000000000000000000");
            gen.writeStringField("after", "bbbb2222");
            gen.writeBooleanField("beforeIsEmpty", true);
            gen.writeBooleanField("afterIsEmpty", false);
            gen.writeEndObject();
            gen.writeEndArray();
            gen.writeEndObject();
        });

        await().atMost(30, TimeUnit.SECONDS).until(() -> mb.getItem("feature") != null);
        r.waitUntilNoActivity();
    }

    @Test
    void pullRequestCreatedTriggersIndexing() throws Exception {
        mockServer
                .addRepo(OWNER, "myrepo", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .branch("feature", "bbbb2222")
                .file("Jenkinsfile", JENKINSFILE);

        OrganizationFolder folder = createOrgFolder();
        r.waitUntilNoActivity();

        WorkflowMultiBranchProject mb = (WorkflowMultiBranchProject) folder.getItem("myrepo");
        assertNotNull(mb);
        // feature branch is a standalone branch (no PR yet)
        assertThat(mb.getItem("PR-1"), nullValue());

        // Add PR to mock server
        mockServer
                .replaceRepo(OWNER, "myrepo", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .branch("feature", "bbbb2222")
                .file("Jenkinsfile", JENKINSFILE)
                .pr(1, "feature", "bbbb2222", "main", "aaaa1111");

        // Deliver pull_request.created webhook
        mockServer.deliverWebhook(webhookUrl, APP_ID, INSTALLATION_ID, "pull_request.created", gen -> {
            gen.writeStartObject();
            gen.writeObjectFieldStart("pullRequest");
            gen.writeStringField("number", "1");
            gen.writeObjectFieldStart("head");
            gen.writeStringField("ref", "feature");
            gen.writeStringField("sha", "bbbb2222");
            gen.writeEndObject();
            gen.writeObjectFieldStart("base");
            gen.writeStringField("ref", "main");
            gen.writeStringField("sha", "aaaa1111");
            gen.writeEndObject();
            gen.writeEndObject();
            gen.writeObjectFieldStart("repository");
            gen.writeObjectFieldStart("owner");
            gen.writeStringField("slug", OWNER);
            gen.writeEndObject();
            gen.writeStringField("name", "myrepo");
            gen.writeEndObject();
            gen.writeEndObject();
        });

        await().atMost(30, TimeUnit.SECONDS).until(() -> mb.getItem("PR-1") != null);
        r.waitUntilNoActivity();
    }

    @Test
    void pullRequestClosedRemovesJob() throws Exception {
        mockServer
                .addRepo(OWNER, "myrepo", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .branch("feature", "bbbb2222")
                .file("Jenkinsfile", JENKINSFILE)
                .pr(1, "feature", "bbbb2222", "main", "aaaa1111");

        OrganizationFolder folder = createOrgFolder();
        r.waitUntilNoActivity();

        WorkflowMultiBranchProject mb = (WorkflowMultiBranchProject) folder.getItem("myrepo");
        assertNotNull(mb);
        assertThat(mb.getItem("PR-1"), notNullValue());

        // Remove PR from mock server
        mockServer
                .replaceRepo(OWNER, "myrepo", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .branch("feature", "bbbb2222")
                .file("Jenkinsfile", JENKINSFILE);

        // Deliver pull_request.closed webhook
        mockServer.deliverWebhook(webhookUrl, APP_ID, INSTALLATION_ID, "pull_request.closed", gen -> {
            gen.writeStartObject();
            gen.writeObjectFieldStart("pullRequest");
            gen.writeStringField("number", "1");
            gen.writeObjectFieldStart("head");
            gen.writeStringField("ref", "feature");
            gen.writeStringField("sha", "bbbb2222");
            gen.writeEndObject();
            gen.writeObjectFieldStart("base");
            gen.writeStringField("ref", "main");
            gen.writeStringField("sha", "aaaa1111");
            gen.writeEndObject();
            gen.writeEndObject();
            gen.writeObjectFieldStart("repository");
            gen.writeObjectFieldStart("owner");
            gen.writeStringField("slug", OWNER);
            gen.writeEndObject();
            gen.writeStringField("name", "myrepo");
            gen.writeEndObject();
            gen.writeEndObject();
        });

        // Phase 1: webhook marks the branch as Dead (dead branch projects are not buildable)
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            var pr1 = mb.getItem("PR-1");
            return pr1 == null || !pr1.isBuildable();
        });

        // Phase 2: re-index causes the dead branch to be cleaned up
        mb.scheduleBuild2(0).getFuture().get(60, TimeUnit.SECONDS);
        r.waitUntilNoActivity();
        assertThat(mb.getItem("PR-1"), nullValue());
    }

    @Test
    void invalidSignatureIsRejected() throws Exception {
        // POST a webhook with a tampered body (signature won't match)
        String originalBody = "{\"deliveryId\":\"whd_test\",\"event\":{\"type\":\"ping\",\"payload\":{}}}";
        String tamperedBody = "{\"deliveryId\":\"whd_test\",\"event\":{\"type\":\"malicious\",\"payload\":{}}}";

        // Sign the original but send the tampered body
        long ts = java.time.Instant.now().getEpochSecond();
        var headers = OriginWebhookVerifierTest.signedHeaders(
                "whd_test", ts, originalBody.getBytes(StandardCharsets.UTF_8), getServerKeyPair());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .header("webhook-id", headers.get("webhook-id"))
                .header("webhook-timestamp", headers.get("webhook-timestamp"))
                .header("webhook-signature", headers.get("webhook-signature"))
                .POST(HttpRequest.BodyPublishers.ofString(tamperedBody))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat("tampered payload must be rejected", resp.statusCode(), is(401));
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        byte[] body = "{\"event\":{\"type\":\"ping\"}}".getBytes(StandardCharsets.UTF_8);
        long ts = java.time.Instant.now().getEpochSecond();

        // Sign with a random key pair (not the mock server's key)
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("Ed25519");
        java.security.KeyPair wrongKey = gen.generateKeyPair();
        var headers = OriginWebhookVerifierTest.signedHeaders("whd_bad", ts, body, wrongKey);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .header("webhook-id", headers.get("webhook-id"))
                .header("webhook-timestamp", headers.get("webhook-timestamp"))
                .header("webhook-signature", headers.get("webhook-signature"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat("wrong key must be rejected", resp.statusCode(), is(401));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private OrganizationFolder createOrgFolder() throws Exception {
        OrganizationFolder folder = r.jenkins.createProject(OrganizationFolder.class, OWNER);
        OriginSCMNavigator navigator = new OriginSCMNavigator(OWNER);
        navigator.setCredentialsId(CREDS_ID);
        folder.getNavigators().add(navigator);
        folder.scheduleBuild2(0).getFuture().get();
        return folder;
    }

    /** Access the mock server's key pair via reflection for the reject test. */
    private java.security.KeyPair getServerKeyPair() throws Exception {
        var field = MockOriginServer.class.getDeclaredField("serverKeyPair");
        field.setAccessible(true);
        return (java.security.KeyPair) field.get(mockServer);
    }
}
