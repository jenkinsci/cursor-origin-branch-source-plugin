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
import io.jenkins.plugins.cursor_origin_branch_source.BranchDiscoveryTrait;
import io.jenkins.plugins.cursor_origin_branch_source.MockOriginServer;
import io.jenkins.plugins.cursor_origin_branch_source.MockOriginServerTestBase;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import io.jenkins.plugins.cursor_origin_branch_source.PullRequestDiscoveryTrait;
import java.util.List;
import jenkins.branch.BranchSource;
import jenkins.scm.api.trait.SCMSourceTrait;
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

    private static final String JUNIT_JENKINSFILE = "node {\n"
            + "  writeFile file: 'results.xml', text: '''" + JUNIT_REPORT + "'''\n"
            + "  junit 'results.xml'\n"
            + "}\n";

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
        assertThat(checkRun.getOutputTitle(), is(notNullValue()));
    }

    /**
     * Nothing is reported before the build exists. The checks API offers to report a job as queued, but
     * the commit to report against is only known once the build has recorded it, so taking that offer
     * would mean guessing a branch head and possibly reporting against a commit that is never built.
     */
    @Test
    void reportsNothingUntilTheBuildKnowsItsCommit() throws Exception {
        mockServer.addRepo(OWNER, "pistons", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);

        createProject("pistons", new OriginChecksTrait());

        MockOriginServer.MockCheckRun checkRun = mockServer.checkRun(OWNER, "pistons", "Jenkins");
        // The completed report is the only one, so nothing was reported while the job was queued.
        assertThat(checkRun.reportedStates(), contains("completed/success"));
        assertThat(checkRun.getHeadSha(), is(MAIN_SHA));
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
     * Test reports are not this plugin's work: the junit plugin publishes them through the checks API,
     * which routes them to our publisher. The point of this test is that such a check reaches Origin as
     * its own check run in the same suite, carrying the failure detail the user would see on GitHub.
     */
    @Test
    void reportsTestResultsAsTheirOwnCheckRun() throws Exception {
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
        assertThat(
                tests.getSuiteKey(),
                is(mockServer.checkRun(OWNER, "sprockets", "Jenkins").getSuiteKey()));
        assertThat(tests.getSuiteKey(), is(project.getFullName()));
    }

    /**
     * Failing tests only make the build UNSTABLE, and an unstable build is reported as a failure rather
     * than as neutral, so a required check does not pass on a build with broken tests.
     */
    @Test
    void reportsAnUnstableBuildAsAFailure() throws Exception {
        mockServer.addRepo(OWNER, "flywheels", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JUNIT_JENKINSFILE);

        WorkflowMultiBranchProject project = createProject("flywheels", new OriginChecksTrait());

        assertThat(project.getItem("main").getLastBuild().getResult(), is(Result.UNSTABLE));
        assertThat(mockServer.checkRun(OWNER, "flywheels", "Jenkins").getConclusion(), is("failure"));
    }

    @Test
    void reportsNothingWhenCheckReportingIsSkipped() throws Exception {
        mockServer.addRepo(OWNER, "cogs", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);
        OriginChecksTrait trait = new OriginChecksTrait();
        trait.setSkip(true);

        createProject("cogs", trait);

        assertThat(mockServer.checkRuns(OWNER, "cogs"), is(empty()));
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
