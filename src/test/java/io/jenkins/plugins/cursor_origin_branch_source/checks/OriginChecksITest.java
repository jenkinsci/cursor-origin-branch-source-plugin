package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

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

    @Test
    void reportsTheBuildUnderTheConfiguredCheckName() throws Exception {
        mockServer.addRepo(OWNER, "sprockets", "main").branch("main", MAIN_SHA).file("Jenkinsfile", JENKINSFILE);
        OriginChecksTrait trait = new OriginChecksTrait();
        trait.setName("continuous-integration/jenkins");

        createProject("sprockets", trait);

        assertThat(
                mockServer
                        .checkRun(OWNER, "sprockets", "continuous-integration/jenkins")
                        .getConclusion(),
                is("success"));
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
