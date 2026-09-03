package io.jenkins.plugins.cursor_origin_branch_source;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import com.cloudbees.hudson.plugins.folder.computed.FolderComputation;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.util.Secret;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Base class for tests that exercise plugin code against a {@link MockOriginServer}.
 *
 * <p>Authentication is real: a genuine Ed25519 key pair is generated for the test app, the
 * credentials object signs a proper JWT, and the mock server verifies it before issuing an
 * {@code oit_} access token. All other API responses are served from in-memory state.
 */
@WithJenkins
abstract class MockOriginServerTestBase {

    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    static final String OWNER = "acme-corp";
    static final String APP_ID = "test-app-1";
    static final String INSTALLATION_ID = "inst-42";
    static final String CREDS_ID = "origin-test-creds";

    JenkinsRule r;

    @AutoClose
    MockOriginServer mockServer;

    private String savedBaseUri;

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        this.r = r;
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair appKeyPair = gen.generateKeyPair();

        mockServer = new MockOriginServer();
        mockServer.registerApp(APP_ID, appKeyPair.getPublic());
        String mockUrl = mockServer.start();

        savedBaseUri = CursorOriginAppCredentials.API_BASE_URI;
        CursorOriginAppCredentials.API_BASE_URI = mockUrl;

        CursorOriginAppCredentials creds = new CursorOriginAppCredentials(
                CredentialsScope.GLOBAL,
                CREDS_ID,
                "Test app credentials",
                APP_ID,
                INSTALLATION_ID,
                Secret.fromString(toPkcs8Pem(appKeyPair)));
        CredentialsStore store =
                CredentialsProvider.lookupStores(r.jenkins).iterator().next();
        store.addCredentials(Domain.global(), creds);
    }

    @AfterEach
    void tearDown() {
        CursorOriginAppCredentials.API_BASE_URI = savedBaseUri;
    }

    static void showIndexing(ComputedFolder<?> folder) throws Exception {
        FolderComputation<?> computation = folder.getComputation();
        System.out.println("---%<--- " + computation.getUrl());
        computation.writeWholeLogTo(System.out);
        System.out.println("---%<--- ");
    }

    /** Encodes an Ed25519 private key as a PKCS#8 PEM string (what the credentials class parses). */
    static String toPkcs8Pem(KeyPair keyPair) {
        byte[] encoded = keyPair.getPrivate().getEncoded();
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }
}
