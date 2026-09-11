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
import java.util.List;
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
public abstract class MockOriginServerTestBase {

    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    protected static final String OWNER = "acme-corp";
    protected static final String APP_ID = "test-app-1";
    protected static final String INSTALLATION_ID = "inst-42";
    protected static final String CREDS_ID = "origin-test-creds";

    protected JenkinsRule r;

    @AutoClose
    protected MockOriginServer mockServer;

    @AutoClose
    MockGitServer mockGitServer;

    protected KeyPair appKeyPair;

    private String savedBaseUri;
    private String savedGitBaseUrl;
    private OriginAppCredentials credentials;

    @BeforeEach
    protected void setUp(JenkinsRule r) throws Exception {
        this.r = r;
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        appKeyPair = gen.generateKeyPair();

        mockServer = new MockOriginServer();
        mockServer.registerApp(
                APP_ID,
                appKeyPair.getPublic(),
                List.of("repository:contents:read", "repository:pull_requests:read", "repository:checks:write"));
        String mockUrl = mockServer.start();

        mockGitServer = new MockGitServer(mockServer.serverKeyPair().getPublic());
        String gitUrl = mockGitServer.start();

        savedBaseUri = OriginAppCredentials.API_BASE_URI;
        OriginAppCredentials.API_BASE_URI = mockUrl;

        savedGitBaseUrl = OriginSCMSource.GIT_BASE_URL;
        OriginSCMSource.GIT_BASE_URL = gitUrl;

        credentials = new OriginAppCredentials(
                CredentialsScope.GLOBAL,
                CREDS_ID,
                "Test app credentials",
                APP_ID,
                INSTALLATION_ID,
                Secret.fromString(toPkcs8Pem(appKeyPair)));
        CredentialsStore store =
                CredentialsProvider.lookupStores(r.jenkins).iterator().next();
        store.addCredentials(Domain.global(), credentials);

        r.jenkins.setQuietPeriod(0);
    }

    /** The app credentials registered in the Jenkins credentials store under {@link #CREDS_ID}. */
    protected OriginAppCredentials credentials() {
        return credentials;
    }

    @AfterEach
    protected void tearDown() {
        OriginAppCredentials.API_BASE_URI = savedBaseUri;
        OriginSCMSource.GIT_BASE_URL = savedGitBaseUrl;
    }

    protected static void showIndexing(ComputedFolder<?> folder) throws Exception {
        FolderComputation<?> computation = folder.getComputation();
        System.out.println("---%<--- " + computation.getUrl());
        computation.writeWholeLogTo(System.out);
        System.out.println("---%<--- ");
    }

    /** Encodes an Ed25519 private key as a PKCS#8 PEM string (what the credentials class parses). */
    protected static String toPkcs8Pem(KeyPair keyPair) {
        byte[] encoded = keyPair.getPrivate().getEncoded();
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }
}
