package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.model.Item;
import hudson.util.Secret;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.branch.NoTriggerBranchProperty;
import jenkins.branch.NoTriggerOrganizationFolderProperty;
import jenkins.branch.OrganizationFolder;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Integration tests for branch and PR discovery against a {@link MockOriginServer}.
 *
 * <p>Authentication is real: a genuine Ed25519 key pair is generated for the test app, the
 * credentials object signs a proper JWT, and the mock server verifies it before issuing an
 * {@code oit_} access token. All other API responses are served from in-memory state.
 *
 * <p>No actual Git checkouts happen. The multibranch projects are configured with
 * <em>Branch names to build automatically</em> set to {@code <none>} so that discovered branch
 * projects are created but not built (pending {@code SCMFileSystem} support in issue #11).
 */
@WithJenkins
class OriginSCMNavigatorTest {

    private static final String OWNER = "acme-corp";
    private static final String APP_ID = "test-app-1";
    private static final String INSTALLATION_ID = "inst-42";
    private static final String CREDS_ID = "origin-test-creds";

    private JenkinsRule r;

    @AutoClose
    private MockOriginServer mockServer;

    private String savedBaseUri;

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        this.r = r;
        // Generate a real Ed25519 key pair for the test app
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair appKeyPair = gen.generateKeyPair();

        // Encode private key as PKCS#8 PEM (what CursorOriginAppCredentials expects)
        String privatePem = toPkcs8Pem(appKeyPair);

        // Start the mock server and register the app's public key
        mockServer = new MockOriginServer();
        mockServer.registerApp(APP_ID, appKeyPair.getPublic());
        String mockUrl = mockServer.start();

        // Point the plugin at the mock server
        savedBaseUri = CursorOriginAppCredentials.API_BASE_URI;
        CursorOriginAppCredentials.API_BASE_URI = mockUrl;

        // Register credentials in Jenkins
        CursorOriginAppCredentials creds = new CursorOriginAppCredentials(
                CredentialsScope.GLOBAL,
                CREDS_ID,
                "Test app credentials",
                APP_ID,
                INSTALLATION_ID,
                Secret.fromString(privatePem));
        CredentialsStore store =
                CredentialsProvider.lookupStores(r.jenkins).iterator().next();
        store.addCredentials(Domain.global(), creds);
    }

    @AfterEach
    void tearDown() {
        CursorOriginAppCredentials.API_BASE_URI = savedBaseUri;
    }

    /**
     * A single repo with two branches and one open PR.
     * Expected result: main + PR-1 (the feature branch is excluded as the PR head).
     */
    @Test
    void singleRepoWithBranchesAndPR() throws Exception {
        mockServer
                .addRepo(OWNER, "widgets", "main")
                .branch("main", "aaaa1111")
                .branch("feature-x", "bbbb2222")
                .file("Jenkinsfile")
                .pr(1, "feature-x", "bbbb2222", "main", "aaaa1111");

        WorkflowMultiBranchProject mbp = createMultiBranchProject("widgets");
        this.r.waitUntilNoActivity();

        assertThat(mbp.getItems().stream().map(Item::getName).toList(), containsInAnyOrder("main", "PR-1"));
    }

    /**
     * Two branches and no open PRs: both branches should be discovered.
     */
    @Test
    void twoBranchesNoPRs() throws Exception {
        mockServer
                .addRepo(OWNER, "gadgets", "main")
                .branch("main", "aaaa1111")
                .branch("develop", "cccc3333")
                .file("Jenkinsfile");

        WorkflowMultiBranchProject mbp = createMultiBranchProject("gadgets");
        this.r.waitUntilNoActivity();

        assertThat(mbp.getItems().stream().map(Item::getName).toList(), containsInAnyOrder("main", "develop"));
    }

    /**
     * OrganizationFolder scanning two repos: navigator discovers both and creates one
     * multibranch project per repo.
     */
    @Test
    void organizationFolderDiscoversTwoRepos() throws Exception {
        mockServer.addRepo(OWNER, "alpha", "main").branch("main", "aaaa1111").file("Jenkinsfile");
        mockServer
                .addRepo(OWNER, "beta", "main")
                .branch("main", "dddd4444")
                .branch("hotfix", "eeee5555")
                .file("Jenkinsfile")
                .pr(3, "hotfix", "eeee5555", "main", "dddd4444");

        OrganizationFolder folder = this.r.jenkins.createProject(OrganizationFolder.class, "acme");
        folder.getProperties().add(new NoTriggerOrganizationFolderProperty("^$"));
        OriginSCMNavigator navigator = new OriginSCMNavigator(OWNER);
        navigator.setCredentialsId(CREDS_ID);
        folder.getNavigators().add(navigator);
        folder.scheduleBuild2(0).getFuture().get();
        this.r.waitUntilNoActivity();

        var projects = folder.getItems();
        assertThat(projects, hasSize(2));
        var projectNames = projects.stream().map(Item::getName).toList();
        assertThat(projectNames, containsInAnyOrder("alpha", "beta"));

        WorkflowMultiBranchProject betaMbp = (WorkflowMultiBranchProject) folder.getItem("beta");
        assertNotNull(betaMbp);
        assertThat(betaMbp.getItems().stream().map(Item::getName).toList(), containsInAnyOrder("main", "PR-3"));
    }

    /**
     * When the only branch is also the head of an open PR, the PR appears and the branch
     * does not (to avoid double-counting).
     */
    @Test
    void prHeadBranchExcludedFromBranchDiscovery() throws Exception {
        mockServer
                .addRepo(OWNER, "solo", "main")
                .branch("main", "aaaa1111")
                .branch("only-branch", "ffff6666")
                .file("Jenkinsfile")
                .pr(7, "only-branch", "ffff6666", "main", "aaaa1111");

        WorkflowMultiBranchProject mbp = createMultiBranchProject("solo");
        this.r.waitUntilNoActivity();

        var names = mbp.getItems().stream().map(Item::getName).toList();
        assertThat("PR head branch must not appear as a branch job", names, not(containsInAnyOrder("only-branch")));
        assertThat(names, containsInAnyOrder("main", "PR-7"));
    }

    /**
     * Repo without a Jenkinsfile: no branch or PR jobs are created because the SCMProbe
     * returns NONEXISTENT for the criteria check.
     */
    @Test
    void noJenkinsfileProducesNoJobs() throws Exception {
        mockServer.addRepo(OWNER, "empty-repo", "main").branch("main", "aaaa1111");
        // no .file("Jenkinsfile") call

        WorkflowMultiBranchProject mbp = createMultiBranchProject("empty-repo");
        this.r.waitUntilNoActivity();

        assertThat(mbp.getItems(), is(empty()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private WorkflowMultiBranchProject createMultiBranchProject(String repoName) throws Exception {
        WorkflowMultiBranchProject mbp =
                this.r.jenkins.createProject(WorkflowMultiBranchProject.class, repoName + "-pipeline");
        OriginSCMSource source = new OriginSCMSource(OWNER, repoName);
        source.setCredentialsId(CREDS_ID);
        source.setTraits(List.of(new BranchDiscoveryTrait(), new PullRequestDiscoveryTrait()));
        BranchSource branchSource = new BranchSource(source);
        branchSource.setStrategy(
                new DefaultBranchPropertyStrategy(new NoTriggerBranchProperty[] {new NoTriggerBranchProperty()}));
        mbp.getSourcesList().add(branchSource);
        mbp.scheduleBuild2(0).getFuture().get();
        return mbp;
    }

    /** Encodes an Ed25519 private key as a PKCS#8 PEM string (what the credentials class parses). */
    private static String toPkcs8Pem(KeyPair keyPair) {
        byte[] encoded = keyPair.getPrivate().getEncoded(); // PKCS#8 DER by default for EdECPrivateKey
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }
}
