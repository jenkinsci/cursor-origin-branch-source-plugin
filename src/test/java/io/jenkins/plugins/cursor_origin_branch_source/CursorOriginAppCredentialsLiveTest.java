package io.jenkins.plugins.cursor_origin_branch_source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Content;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Repo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Live test for Cursor Origin App authentication.
 * Runs like a unit test outside any Jenkins context, just focusing on the actual token construction,
 * proving that the resulting token can actually be used to call representative Origin REST APIs.
 * Skipped automatically when the required environment variables are absent (e.g. in CI).
 *
 * Required env vars:
 *   CURSOR_ORIGIN_APP_ID          – app identifier used as the JWT issuer
 *   CURSOR_ORIGIN_APP_PK_FILE     – path to a PKCS#8 PEM Ed25519 private key file
 *   CURSOR_ORIGIN_INSTALLATION_ID – installation ID to scope the token to
 *   CURSOR_ORIGIN_TEST_REPO_OWNER – owner slug of a repo accessible to the installation
 *   CURSOR_ORIGIN_TEST_REPO_NAME  – name of that repo
 */
class CursorOriginAppCredentialsLiveTest {

    @Test
    void appAuthCanReadRepoContents() throws Exception {
        String appId = System.getenv("CURSOR_ORIGIN_APP_ID");
        String pkFile = System.getenv("CURSOR_ORIGIN_APP_PK_FILE");
        String installationId = System.getenv("CURSOR_ORIGIN_INSTALLATION_ID");
        String ownerSlug = System.getenv("CURSOR_ORIGIN_TEST_REPO_OWNER");
        String repoName = System.getenv("CURSOR_ORIGIN_TEST_REPO_NAME");
        assumeTrue(
                appId != null && pkFile != null && installationId != null && ownerSlug != null && repoName != null,
                "Skipping: CURSOR_ORIGIN_APP_ID, CURSOR_ORIGIN_APP_PK_FILE,"
                        + " CURSOR_ORIGIN_INSTALLATION_ID, CURSOR_ORIGIN_TEST_REPO_OWNER,"
                        + " CURSOR_ORIGIN_TEST_REPO_NAME must all be set");

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
}
