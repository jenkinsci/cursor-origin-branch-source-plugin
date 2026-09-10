package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.model.Label;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.util.Secret;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import jenkins.branch.BranchSource;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.LogRecorder;

/**
 * Tests for token scoping behavior (issue #16: properly scope access tokens to relevant repo).
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>1. REST-only indexing: no git operations → token is unrestricted
 *   <li>2.i MBP {@code checkout scm}: token must be scoped to the specific repo
 *   <li>2.ii Standalone project with {@code CpsScmFlowDefinition}: same scoping required
 *   <li>3. {@code @Library} controller clone: token need not be scoped
 *   <li>4.a {@code withGit} unrestricted credential: git clone via credential helper binding
 *   <li>4.b {@code withCredentials} unrestricted credential: git clone via URL-embedded credentials
 *   <li>4.c {@code withCredentials} unrestricted credential: REST API call with Bearer token
 *   <li>4 (restricted): {@code withCredentials} with restricted credential must fail immediately
 * </ul>
 */
class TokenScopingTest extends MockOriginServerTestBase {

    @AutoClose
    LogRecorder logging = new LogRecorder().record(OriginAppCredentials.class, Level.FINE);

    @BeforeEach
    void setUpAgent() throws Exception {
        r.createSlave(Label.get("remote"));
    }

    // ── 1: REST-only indexing ────────────────────────────────────────────────

    /**
     * Scenario 1: branch indexing uses only REST API calls; the git server is never contacted.
     * Therefore, no token needs to be scoped to a specific repo.
     */
    @Test
    void branchIndexingUsesUnrestrictedToken() throws Exception {
        mockServer
                .addRepo(OWNER, "api-only-repo", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", "echo 'hello'");

        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "p");
        OriginSCMSource source = new OriginSCMSource(OWNER, "api-only-repo");
        source.setCredentialsId(CREDS_ID);
        source.setTraits(List.of(new BranchDiscoveryTrait()));
        mbp.getSourcesList().add(new BranchSource(source));
        mbp.scheduleBuild2(0).getFuture().get();
        showIndexing(mbp);

        // Git server should not have been contacted for indexing
        assertThat(mockGitServer.getLastAuth(OWNER, "api-only-repo"), is(nullValue()));
    }

    // ── 2.i: MBP checkout scm ───────────────────────────────────────────────

    /**
     * Scenario 2.i: when a multibranch pipeline Jenkinsfile runs {@code checkout scm} on an
     * agent, the installation token minted for the git clone must be scoped to only that repo.
     */
    @Test
    void multiBranchCheckoutScopedToRepo() throws Exception {
        MockOriginServer.MockRepo mockRepo = mockServer.addRepo(OWNER, "checkout-repo", "main");
        String sha = mockGitServer.addRepo(OWNER, "checkout-repo", mockRepo.id, "main", Map.of("data.txt", "hello"));
        mockRepo.branch("main", sha).file("Jenkinsfile", "node('remote') { checkout scm }");

        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "p");
        OriginSCMSource source = new OriginSCMSource(OWNER, "checkout-repo");
        source.setCredentialsId(CREDS_ID);
        source.setTraits(List.of(new BranchDiscoveryTrait()));
        mbp.getSourcesList().add(new BranchSource(source));
        mbp.scheduleBuild2(0).getFuture().get();
        r.waitUntilNoActivity();

        WorkflowJob job = mbp.getItem("main");
        assertThat("branch job was created", job != null);
        WorkflowRun build = job.getLastBuild();
        assertThat("branch job was built", build != null);
        assertThat(build.getResult(), is(Result.SUCCESS));

        MockGitServer.LastAuth auth = mockGitServer.getLastAuth(OWNER, "checkout-repo");
        assertThat("git server was contacted", auth != null);
        assertThat(auth.repositoryIds(), contains(mockRepo.id));
        assertThat(auth.scopes(), containsInAnyOrder("repository:metadata:read", "repository:contents:read"));
    }

    // ── 2.ii: standalone CpsScmFlowDefinition ───────────────────────────────

    /**
     * Scenario 2.ii: a standalone pipeline job using {@link CpsScmFlowDefinition} also needs a
     * repo-scoped token for the git clone.
     *
     * <p>Note: lightweight mode ({@code setLightweight(true)}) would require
     * {@code OriginSCMFileSystem.BuilderImpl.supports(SCM)} to recognize {@link GitSCM}, which is
     * not yet implemented.
     */
    @Test
    void standaloneProjectCheckoutScopedToRepo() throws Exception {
        MockOriginServer.MockRepo mockRepo = mockServer.addRepo(OWNER, "standalone-repo", "main");
        String sha = mockGitServer.addRepo(
                OWNER, "standalone-repo", mockRepo.id, "main", Map.of("Jenkinsfile", "node('remote') {checkout scm}"));
        mockRepo.branch("main", sha);

        WorkflowJob job = r.createProject(WorkflowJob.class, "p");
        String repoUrl = OriginSCMSource.GIT_BASE_URL + "/" + OWNER + "/standalone-repo.git";
        GitSCM scm = new GitSCM(
                List.of(new UserRemoteConfig(repoUrl, "origin", null, CREDS_ID)),
                List.of(new BranchSpec("*/main")),
                null,
                null,
                List.of());
        CpsScmFlowDefinition def = new CpsScmFlowDefinition(scm, "Jenkinsfile");
        // TODO: lightweight mode requires OriginSCMFileSystem.BuilderImpl.supports(GitSCM) recognition
        def.setLightweight(false);
        job.setDefinition(def);

        WorkflowRun build = r.buildAndAssertSuccess(job);
        assertThat(build.getResult(), is(Result.SUCCESS));

        MockGitServer.LastAuth auth = mockGitServer.getLastAuth(OWNER, "standalone-repo");
        assertThat("git server was contacted", auth != null);
        assertThat(auth.repositoryIds(), contains(mockRepo.id));
        assertThat(auth.scopes(), containsInAnyOrder("repository:metadata:read", "repository:contents:read"));
    }

    // ── 3: @Library controller clone ────────────────────────────────────────

    /**
     * Scenario 3: when a pipeline requests a {@code @Library} whose source is an
     * {@link OriginSCMSource}, the library is cloned on the controller. The token used for this
     * clone need not be scoped to any specific repo — controller-side library retrieval is
     * intentionally unrestricted.
     */
    @Test
    void libraryCloneOnControllerUsesUnrestrictedToken() throws Exception {
        MockOriginServer.MockRepo libRepo = mockServer.addRepo(OWNER, "lib-repo", "main");
        String sha = mockGitServer.addRepo(
                OWNER, "lib-repo", libRepo.id, "main", Map.of("vars/myStep.groovy", "def call() {}"));
        libRepo.branch("main", sha);

        OriginSCMSource libSource = new OriginSCMSource(OWNER, "lib-repo");
        libSource.setCredentialsId(CREDS_ID);
        LibraryConfiguration lib = new LibraryConfiguration("my-lib", new SCMSourceRetriever(libSource));
        lib.setDefaultVersion("main");
        GlobalLibraries.get().setLibraries(List.of(lib));

        WorkflowJob job = r.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition("@Library('my-lib') _\nnode('remote') { echo 'done' }", true));
        r.buildAndAssertSuccess(job);

        MockGitServer.LastAuth libAuth = mockGitServer.getLastAuth(OWNER, "lib-repo");
        assertThat("library git server was contacted", libAuth != null);
        // Library clones on the controller use unrestricted tokens (intentional)
        assertThat(libAuth.repositoryIds(), is(empty()));
        assertThat(
                libAuth.scopes(),
                containsInAnyOrder(
                        "repository:metadata:read",
                        "repository:contents:read",
                        "repository:pull_requests:read",
                        "repository:checks:write"));
    }

    // ── 4.a: gitUsernamePassword unrestricted credential ────────────────────

    /**
     * Scenario 4.a: {@code withCredentials([gitUsernamePassword(...)])} running a git clone
     * succeeds when the credential is unrestricted.
     */
    @Test
    void withGitUnrestrictedSucceeds() throws Exception {
        mockGitServer.addRepo(OWNER, "git-repo", "main", Map.of("file.txt", "hello"));
        addUnrestrictedCredentials("origin-unrestricted-creds");

        WorkflowJob job = r.createProject(WorkflowJob.class, "p");
        job.addProperty(new ParametersDefinitionProperty(List.of(new StringParameterDefinition("REPO_URL", ""))));
        job.setDefinition(new CpsFlowDefinition("""
                node('remote') {
                  withCredentials([gitUsernamePassword(credentialsId: 'origin-unrestricted-creds', gitToolName: 'Default')]) {
                    sh 'git clone "$REPO_URL" cloned'
                  }
                }
                """, true));
        r.assertBuildStatus(
                Result.SUCCESS,
                job.scheduleBuild2(
                                0,
                                new ParametersAction(new StringParameterValue(
                                        "REPO_URL", mockGitServer.baseUrl() + "/" + OWNER + "/git-repo.git")))
                        .get());
    }

    // ── 4.b: withCredentials unrestricted credential (clone) ─────────────────

    /**
     * Scenario 4.b: {@code withCredentials} with a {@code usernameColonPassword} binding embedding
     * credentials in the git clone URL succeeds when the credential is unrestricted.
     */
    @Test
    void withCredentialsUnrestrictedCloneSucceeds() throws Exception {
        mockGitServer.addRepo(OWNER, "clone-repo", "main", Map.of("file.txt", "hello"));
        addUnrestrictedCredentials("origin-unrestricted-creds");

        WorkflowJob job = r.createProject(WorkflowJob.class, "p");
        job.addProperty(new ParametersDefinitionProperty(List.of(new StringParameterDefinition("REPO_BASE", ""))));
        // REPO_BASE is host:port/owner/name.git — credentials are prepended in the URL
        String repoBase = mockGitServer.baseUrl().substring("http://".length()) + "/" + OWNER + "/clone-repo.git";
        job.setDefinition(new CpsFlowDefinition("""
                node('remote') {
                  withCredentials([usernameColonPassword(credentialsId: 'origin-unrestricted-creds', variable: 'CREDS')]) {
                    sh 'git clone "http://$CREDS@$REPO_BASE" cloned'
                  }
                }
                """, true));
        r.assertBuildStatus(
                Result.SUCCESS,
                job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("REPO_BASE", repoBase)))
                        .get());
    }

    // ── 4.c: withCredentials unrestricted credential (REST API) ──────────────

    /**
     * Scenario 4.c: {@code withCredentials} with a {@code usernamePassword} binding exposes the
     * {@code oit_} token as a shell variable, which can be used as a Bearer token for REST API calls.
     */
    @Test
    void withCredentialsUnrestrictedCurlSucceeds() throws Exception {
        addUnrestrictedCredentials("origin-unrestricted-creds");

        WorkflowJob job = r.createProject(WorkflowJob.class, "p");
        job.addProperty(new ParametersDefinitionProperty(List.of(new StringParameterDefinition("REST_URL", ""))));
        job.setDefinition(new CpsFlowDefinition("""
                node('remote') {
                  withCredentials([usernamePassword(credentialsId: 'origin-unrestricted-creds',
                      usernameVariable: 'USER', passwordVariable: 'TOKEN')]) {
                    sh 'curl -sf -H "Authorization: Bearer $TOKEN" "$REST_URL"'
                  }
                }
                """, true));
        r.assertBuildStatus(
                Result.SUCCESS,
                job.scheduleBuild2(
                                0,
                                new ParametersAction(new StringParameterValue(
                                        "REST_URL", mockServer.baseUrl() + "/v1/origin/installation/repos")))
                        .get());
    }

    // ── 4 (restricted): withCredentials restricted credential ────────────────

    /**
     * Scenario 4 (restricted): using a restricted {@code OriginAppCredentials} (i.e.
     * {@code unrestricted=false}) in a {@code withCredentials} step must fail immediately — the
     * step should refuse to bind the credential.
     */
    @Test
    void withCredentialsRestrictedThrows() throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "p");
        job.addProperty(new ParametersDefinitionProperty(List.of(new StringParameterDefinition("REST_URL", ""))));
        job.setDefinition(new CpsFlowDefinition("""
                node('remote') {
                  withCredentials([usernamePassword(credentialsId: 'origin-test-creds',
                      usernameVariable: 'USER', passwordVariable: 'TOKEN')]) {
                    sh 'curl -sf -H "Authorization: Bearer $TOKEN" "$REST_URL"'
                  }
                }
                """, true));
        WorkflowRun build = r.assertBuildStatus(
                Result.FAILURE,
                job.scheduleBuild2(
                                0,
                                new ParametersAction(new StringParameterValue(
                                        "REST_URL", mockServer.baseUrl() + "/v1/origin/installation/repos")))
                        .get());
        r.assertLogContains("Cannot use restricted credentials", build);
    }

    // ── 4 (restricted): gitUsernamePassword restricted credential ───────────

    /**
     * Scenario 4 (restricted): using a restricted {@code OriginAppCredentials} in a
     * {@code withCredentials([gitUsernamePassword(...)])} step must also fail immediately.
     */
    @Test
    void withGitRestrictedThrows() throws Exception {
        mockGitServer.addRepo(OWNER, "git-repo", "main", Map.of("file.txt", "hello"));

        WorkflowJob job = r.createProject(WorkflowJob.class, "p");
        job.addProperty(new ParametersDefinitionProperty(List.of(new StringParameterDefinition("REPO_URL", ""))));
        job.setDefinition(new CpsFlowDefinition("""
                node('remote') {
                  withCredentials([gitUsernamePassword(credentialsId: 'origin-test-creds', gitToolName: 'Default')]) {
                    sh 'git clone "$REPO_URL" cloned'
                  }
                }
                """, true));
        WorkflowRun build = r.assertBuildStatus(
                Result.FAILURE,
                job.scheduleBuild2(
                                0,
                                new ParametersAction(new StringParameterValue(
                                        "REPO_URL", mockGitServer.baseUrl() + "/" + OWNER + "/git-repo.git")))
                        .get());
        r.assertLogContains("Cannot use restricted credentials", build);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void addUnrestrictedCredentials(String id) throws Exception {
        OriginAppCredentials creds = new OriginAppCredentials(
                CredentialsScope.GLOBAL,
                id,
                "Unrestricted test credentials",
                APP_ID,
                INSTALLATION_ID,
                Secret.fromString(toPkcs8Pem(appKeyPair)));
        creds.setUnrestricted(true);
        CredentialsStore store =
                CredentialsProvider.lookupStores(r.jenkins).iterator().next();
        store.addCredentials(Domain.global(), creds);
    }
}
