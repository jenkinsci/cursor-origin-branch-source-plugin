package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Content;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Repo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import jenkins.agents.ControllerToAgentFileCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Live tests for Cursor Origin App authentication.
 * Skipped automatically when the required environment variables are absent (e.g. in CI).
 *
 * Required env vars:
 *   CURSOR_ORIGIN_APP_ID          – app identifier used as the JWT issuer, displayed at https://cursor.com/codebase/settings/apps/$slug
 *   CURSOR_ORIGIN_APP_PK_FILE     – path to a PKCS#8 PEM Ed25519 private key file {@code origin-app-private.pem} https://cursor.com/docs/api/origin#generate-an-app-signing-key
 *   CURSOR_ORIGIN_INSTALLATION_ID – installation ID to scope the token to; take from URL https://cursor.com/codebase/settings/apps/installations/$id
 *   CURSOR_ORIGIN_TEST_REPO_OWNER – owner slug of a repo accessible to the installation https://cursor.com/codebase/$owner/$name/settings/apps
 *   CURSOR_ORIGIN_TEST_REPO_NAME  – name of that repo
 */
@WithJenkins
class CursorOriginAppCredentialsLiveTest {

    private static final String appId = System.getenv("CURSOR_ORIGIN_APP_ID");
    private static final String pkFile = System.getenv("CURSOR_ORIGIN_APP_PK_FILE");
    private static final String installationId = System.getenv("CURSOR_ORIGIN_INSTALLATION_ID");
    private static final String ownerSlug = System.getenv("CURSOR_ORIGIN_TEST_REPO_OWNER");
    private static final String repoName = System.getenv("CURSOR_ORIGIN_TEST_REPO_NAME");

    @BeforeAll
    static void checkVars() {
        assumeTrue(
                appId != null && pkFile != null && installationId != null && ownerSlug != null && repoName != null,
                "Skipping: CURSOR_ORIGIN_APP_ID, CURSOR_ORIGIN_APP_PK_FILE,"
                        + " CURSOR_ORIGIN_INSTALLATION_ID, CURSOR_ORIGIN_TEST_REPO_OWNER,"
                        + " CURSOR_ORIGIN_TEST_REPO_NAME must all be set");
    }

    /**
     * Runs like a unit test outside any Jenkins context, just focusing on the actual token construction,
     * proving that the resulting token can actually be used to call representative Origin REST APIs.
     */
    @Test
    void appAuthCanReadRepoContents() throws Exception {
        String token = CursorOriginAppCredentials.doMintToken(appId, installationId, Files.readString(Path.of(pkFile)));

        OriginServiceApi api = CursorOriginAppCredentials.apiWithToken(token);

        Repo repo = api.originServiceGetRepo(ownerSlug, repoName);
        assertEquals(repoName, repo.getName(), "Repo name mismatch");

        Content root = api.originServiceGetContents(ownerSlug, repoName, null, repo.getDefaultBranch());
        assertNotNull(root, "Expected root content");
        assertEquals("dir", root.getType(), "Expected root to be a directory");
        assertNotNull(root.getEntries(), "Expected root directory entries");
        assertFalse(root.getEntries().isEmpty(), "Expected at least one entry in root directory");
        System.out.println("Root listing of " + ownerSlug + "/" + repoName + " @ " + repo.getDefaultBranch() + ":");
        for (Content entry : root.getEntries()) {
            System.out.println("  " + entry.getType() + "\t" + entry.getName());
        }
    }

    /**
     * Checks both that credentials can be serialized to an agent (fetching an access token on demand) and that HTTPS clones work.
     */
    @Test
    void agentSerialization(JenkinsRule r) throws Exception {
        var creds = new CursorOriginAppCredentials(
                CredentialsScope.GLOBAL,
                "myapp",
                null,
                appId,
                installationId,
                Secret.fromString(Files.readString(Path.of(pkFile))));
        var agent = r.createOnlineSlave();
        var listener = StreamTaskListener.fromStderr();
        var ws = agent.getWorkspaceRoot();
        assertThat(ws, notNullValue());
        ws.mkdirs();
        ws.act(new UseCreds(creds, listener, ownerSlug, repoName));
    }

    /** https://cursor.com/docs/api/origin#git-https-authentication */
    private record UseCreds(StandardUsernamePasswordCredentials creds, TaskListener listener, String owner, String repo)
            implements ControllerToAgentFileCallable<Void> {
        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            assertThat(
                    new Launcher.LocalLauncher(listener)
                            .launch()
                            .pwd(f)
                            .stdout(listener)
                            .cmds(
                                    "git",
                                    "clone",
                                    "https://" + creds.getUsername() + ":" + creds.getPassword() + "@origin.cursor.com/"
                                            + owner + "/" + repo + ".git",
                                    ".")
                            .masks(false, false, true, false)
                            .join(),
                    is(0));
            try (var dir = Files.list(f.toPath())) {
                var entries = dir.map(d -> d.getFileName().toString()).toList();
                listener.getLogger().println("Cloned dir entries: " + entries);
                assertThat(entries, hasItem(".git"));
            }
            return null;
        }
    }
}
