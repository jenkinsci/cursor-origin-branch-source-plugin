package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import hudson.model.Result;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import io.jenkins.plugins.cursor_origin_branch_source.BranchDiscoveryTrait;
import io.jenkins.plugins.cursor_origin_branch_source.MockOriginServer;
import io.jenkins.plugins.cursor_origin_branch_source.MockOriginServer.MockRepo;
import io.jenkins.plugins.cursor_origin_branch_source.MockOriginServerTestBase;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import io.jenkins.plugins.cursor_origin_branch_source.PullRequestDiscoveryTrait;
import io.jenkins.plugins.cursor_origin_branch_source.checks.OriginCheckRerunCause.OriginCheckRerunUserCause;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jenkins.branch.BranchSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.awaitility.Awaitility;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of check reporting: a real multibranch build against {@link MockOriginServer},
 * with nothing between the checks API and Origin stubbed out.
 */
class OriginChecksITest extends MockOriginServerTestBase {

    private static final String JENKINSFILE = "node { echo 'building' }";
    private static final String MAIN_SHA = "aaaa1111";
    private static final String FEATURE_SHA = "bbbb2222";

    /** A report with one failure, one pass and one skip, for the junit step to archive. */
    private static final String JUNIT_REPORT = """
            <?xml version='1.0' encoding='UTF-8'?>
            <testsuite name='com.acme.WidgetTest' tests='3' failures='1' skipped='1'>
              <testcase classname='com.acme.WidgetTest' name='spins'/>
              <testcase classname='com.acme.WidgetTest' name='wobbles'>
                <failure message='expected spin but got wobble'>expected spin but got wobble</failure>
              </testcase>
              <testcase classname='com.acme.WidgetTest' name='ignored'><skipped/></testcase>
            </testsuite>
            """;

    private static final String JUNIT_JENKINSFILE = """
            node {
              writeFile file: 'results.xml', text: '''%s'''
              junit 'results.xml'
            }
            """.formatted(JUNIT_REPORT);

    /** A successful build reports the whole lifecycle of one check against the branch head. */
    @Test
    void reportsABranchBuildAsACheckRun() throws Exception {
        mockServer.addRepo(OWNER, "widgets", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);

        WorkflowMultiBranchProject project = createProject("widgets", new OriginChecksTrait());

        MockOriginServer.MockCheckRun checkRun = mockServer.checkRun(OWNER, "widgets", "Jenkins");
        assertThat(checkRun.getName(), is("Jenkins"));
        assertThat(checkRun.getHeadSha(), is(MAIN_SHA));
        assertThat(checkRun.getStatus(), is("completed"));
        assertThat(checkRun.getConclusion(), is("success"));
        assertThat(checkRun.reportedStates(), hasItem("completed/success"));
        assertThat(checkRun.getSuiteKey(), is(project.getFullName()));
        assertThat(
                checkRun.getExternalId(),
                is(project.getItem("main").getLastBuild().getExternalizableId()));
        assertThat(
                checkRun.getDetailsUrl(), containsString(project.getItem("main").getUrl()));
        assertThat(checkRun.getOutputTitle(), is("Success"));
    }

    /**
     * Nothing is reported before the build exists. The checks API offers to report a job as queued, but
     * the commit to report against is only known once the build has recorded it, so taking that offer
     * would mean guessing a branch head and possibly reporting against a commit that is never built.
     * As this pipeline does not have any checkout scm or other check there can only be a completed status.
     */
    @Test
    void reportsNothingUntilTheBuildKnowsItsCommit() throws Exception {
        mockServer.addRepo(OWNER, "pistons", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);

        createProject("pistons", new OriginChecksTrait());

        MockOriginServer.MockCheckRun checkRun = mockServer.checkRun(OWNER, "pistons", "Jenkins");
        // As there this is a lightweight checkout and there is no checkout scm step there there is no check that knows
        // a revision
        // until the final check.
        assertThat(checkRun.reportedStates(), contains(is("completed/success")));
        assertThat(checkRun.getHeadSha(), is(MAIN_SHA));
    }

    /**
     * Nothing is reported before the build exists. The checks API offers to report a job as queued, but
     * the commit to report against is only known once the build has recorded it, so taking that offer
     * would mean guessing a branch head and possibly reporting against a commit that is never built.
     */
    @Test
    void reportsPendingWhenTheBuildKnowsItsCommit() throws Exception {
        String jf = """
                 node {
                  // checking out here forces the SCMListener which will report an in progress
                  checkout scm
                  echo 'hello'
                }
                """;
        MockRepo repo = mockServer.addRepo(OWNER, "pistons", "main");
        String sha = mockGitServer.addRepo(OWNER, "pistons", repo.id, "main", Map.of("Jenkinsfile", jf));
        repo.branch("main", sha).file("Jenkinsfile", jf);

        WorkflowMultiBranchProject project = createProject("pistons", new OriginChecksTrait());
        WorkflowRun build = project.getBranch("main").getBuildByNumber(1);
        r.assertBuildStatusSuccess(build);

        MockOriginServer.MockCheckRun checkRun = mockServer.checkRun(OWNER, "pistons", "Jenkins");
        // Only the queued report is ruled out. An in-progress one is legitimate — the checks API sends
        // it once the build has a revision which is handled as the checkout scm — so asserting the completed
        // report is the only one would fail the moment a test Jenkinsfile checked out or used a stage.
        assertThat(checkRun.reportedStates(), contains(is("in_progress"), is("completed/success")));
        assertThat(checkRun.getHeadSha(), is(sha));
    }

    /** A pull request has to be reported against its head commit, not the target branch. */
    @Test
    void reportsAPullRequestBuildAgainstItsHeadSha() throws Exception {
        mockServer
                .addRepo(OWNER, "gadgets", "main")
                .branch("main", MAIN_SHA)
                .file("Jenkinsfile", JENKINSFILE)
                .branch("feature-x", FEATURE_SHA)
                .file("Jenkinsfile", JENKINSFILE)
                .pr(1, "feature-x", FEATURE_SHA, "main", MAIN_SHA);

        createProject("gadgets", new OriginChecksTrait());

        List<MockOriginServer.MockCheckRun> checkRuns = mockServer.checkRuns(OWNER, "gadgets");
        assertThat(
                checkRuns.stream()
                        .map(MockOriginServer.MockCheckRun::getHeadSha)
                        .toList(),
                hasItem(FEATURE_SHA));
        assertThat(
                checkRuns.stream()
                        .filter(run -> FEATURE_SHA.equals(run.getHeadSha()))
                        .map(MockOriginServer.MockCheckRun::getConclusion)
                        .toList(),
                hasItem("success"));
    }

    /**
     * A build with a failing test, covering both of the things that follow from it.
     *
     * <p>Test reports are not this plugin's work: the junit plugin publishes them through the checks
     * API, which routes them to our publisher. So the report has to arrive as its own check run in the
     * same suite, carrying the failure detail the user would see on GitHub.
     *
     * <p>The failing test also only makes the build UNSTABLE, and an unstable build has to be reported
     * as a failure rather than as neutral, or a required check would pass on broken tests.
     */
    @Test
    void reportsTestResultsAsTheirOwnCheckRunAndTheBuildAsAFailure() throws Exception {
        mockServer.addRepo(OWNER, "sprockets", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JUNIT_JENKINSFILE);

        WorkflowMultiBranchProject project = createProject("sprockets", new OriginChecksTrait());

        assertThat(
                mockServer.checkRuns(OWNER, "sprockets").stream()
                        .map(MockOriginServer.MockCheckRun::getKey)
                        .toList(),
                containsInAnyOrder("Jenkins", "Tests"));

        MockOriginServer.MockCheckRun tests = mockServer.checkRun(OWNER, "sprockets", "Tests");
        assertThat(tests.getHeadSha(), is(MAIN_SHA));
        assertThat(tests.getStatus(), is("completed"));
        assertThat(tests.getConclusion(), is("failure"));
        assertThat(tests.getOutputTitle(), is("com.acme.WidgetTest.wobbles failed"));
        assertThat(tests.getOutputText(), containsString("com.acme.WidgetTest.wobbles"));
        assertThat(tests.getOutputText(), containsString("expected spin but got wobble"));
        assertThat(tests.getDetailsUrl(), containsString("tests"));
        // Both checks belong to one suite, so Origin groups them under the same build.
        MockOriginServer.MockCheckRun status = mockServer.checkRun(OWNER, "sprockets", "Jenkins");
        assertThat(tests.getSuiteKey(), is(status.getSuiteKey()));
        assertThat(tests.getSuiteKey(), is(project.getFullName()));

        assertThat(project.getItem("main").getLastBuild().getResult(), is(Result.UNSTABLE));
        assertThat(status.getConclusion(), is("failure"));
    }

    @Test
    void reportsNothingWhenCheckReportingIsSkipped() throws Exception {
        mockServer.addRepo(OWNER, "cogs", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);
        OriginChecksTrait trait = new OriginChecksTrait();
        trait.setSkip(true);

        createProject("cogs", trait);

        assertThat(mockServer.checkRuns(OWNER, "cogs"), is(empty()));
    }

    /**
     * When a user clicks "Re-run" on a check run in Origin, a
     * {@code repository.check_run.rerequested} webhook is fired. The plugin must replay the build that produced the check.
     */
    @Test
    void rebuildsWhenCheckRunIsRerequested() throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        FullControlOnceLoggedInAuthorizationStrategy authz = new FullControlOnceLoggedInAuthorizationStrategy();
        authz.setAllowAnonymousRead(false);
        r.jenkins.setAuthorizationStrategy(authz);
        String webhookUrl = r.getURL().toExternalForm() + "cursor-origin-webhook/";
        mockServer.addRepo(OWNER, "retries", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);
        WorkflowMultiBranchProject project = createProject("retries", new OriginChecksTrait());

        WorkflowJob mainJob = project.getItem("main");
        assertThat(mainJob.getLastBuild().getNumber(), is(1));

        String externalId = mockServer.checkRun(OWNER, "retries", "Jenkins").getExternalId();

        deliverRerequest(webhookUrl, "retries", project.getFullName(), externalId);

        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> mainJob.getLastBuild().getNumber() == 2);
        r.waitUntilNoActivity();

        OriginCheckRerunCause cause = mainJob.getBuildByNumber(2).getCause(OriginCheckRerunCause.class);
        assertThat(cause, notNullValue());
        assertThat(cause, Matchers.instanceOf(OriginCheckRerunUserCause.class));
    }

    /**
     * A valid signature only proves Cursor Origin sent the delivery, not that the {@code externalId}
     * it carries belongs to the repository it names. Any repository the app is installed on can put an
     * arbitrary {@code externalId} on one of its own check runs and rerequest it, so a check run from
     * an unrelated repository must not be able to rerun this job.
     */
    @Test
    void ignoresARerequestFromAnotherRepository() throws Exception {
        String webhookUrl = r.getURL().toExternalForm() + "cursor-origin-webhook/";
        mockServer.addRepo(OWNER, "retries", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);
        mockServer.addRepo(OWNER, "strangers", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);
        WorkflowMultiBranchProject project = createProject("retries", new OriginChecksTrait());
        createProject("strangers", new OriginChecksTrait());

        WorkflowJob mainJob = project.getItem("main");
        assertThat(mainJob.getLastBuild().getNumber(), is(1));

        String externalId = mockServer.checkRun(OWNER, "retries", "Jenkins").getExternalId();

        // Names the stranger repository but carries the externalId of the retries build.
        deliverRerequest(webhookUrl, "strangers", project.getFullName(), externalId);

        r.waitUntilNoActivity();

        assertThat(mainJob.getLastBuild().getNumber(), is(1));
    }

    /** Delivers a {@code repository.check_run.rerequested} webhook for {@code repoName}. */
    private void deliverRerequest(String webhookUrl, String repoName, String suiteKey, String externalId)
            throws Exception {
        mockServer.deliverWebhook(webhookUrl, APP_ID, INSTALLATION_ID, "repository.check_run.rerequested", gen -> {
            gen.writeStartObject();
            gen.writeObjectFieldStart("repository");
            gen.writeObjectFieldStart("owner");
            gen.writeStringField("slug", OWNER);
            gen.writeEndObject();
            gen.writeStringField("name", repoName);
            gen.writeEndObject();
            gen.writeObjectFieldStart("checkSuite");
            gen.writeStringField("key", suiteKey);
            gen.writeEndObject();
            gen.writeObjectFieldStart("checkRun");
            gen.writeStringField("sha", MAIN_SHA);
            gen.writeStringField("key", "Jenkins");
            gen.writeStringField("externalId", externalId);
            gen.writeStringField("status", "rerequested");
            gen.writeObjectFieldStart("rerequestedBy");
            gen.writeObjectFieldStart("user");
            gen.writeStringField("email", "joe@example.com");
            gen.writeEndObject();
            gen.writeEndObject();
            gen.writeEndObject();
            gen.writeEndObject();
        });
    }

    /** Builds a multibranch project, indexes it and waits for the branch builds to finish. */
    private WorkflowMultiBranchProject createProject(String repoName, SCMSourceTrait checksTrait) throws Exception {
        WorkflowMultiBranchProject project =
                r.jenkins.createProject(WorkflowMultiBranchProject.class, repoName + "-pipeline");
        OriginSCMSource source = new OriginSCMSource(OWNER, repoName);
        source.setCredentialsId(CREDS_ID);
        source.setTraits(List.of(new BranchDiscoveryTrait(), new PullRequestDiscoveryTrait(), checksTrait));
        project.getSourcesList().add(new BranchSource(source));
        project.scheduleBuild2(0).getFuture().get();
        showIndexing(project);
        r.waitUntilNoActivity();
        return project;
    }
}
