package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import hudson.model.Item;
import hudson.model.Result;
import java.util.List;
import jenkins.branch.BranchSource;
import jenkins.test.RunMatchers;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;

class OriginSCMSourceTest extends MockOriginServerTestBase {

    private static final String JENKINSFILE = "echo \"content: ${readTrusted('some-file')}\"";

    /**
     * A single repo with two branches and one open PR.
     * Expected result: main + PR-1 (the feature branch is excluded as the PR head).
     * Both jobs run via SCMFileSystem lightweight checkout and load branch-specific content.
     */
    @Test
    void singleRepoWithBranchesAndPR() throws Exception {
        mockServer
                .addRepo(OWNER, "widgets", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "widgets-main")
                .branch("feature-x", "bbbb2222")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "widgets-feature-x")
                .pr(1, "feature-x", "bbbb2222", "main", "aaaa1111");

        WorkflowMultiBranchProject mbp = createMultiBranchProject("widgets");
        r.waitUntilNoActivity();

        assertThat(mbp.getItems().stream().map(Item::getName).toList(), containsInAnyOrder("main", "PR-1"));
        assertBuildSucceeded(mbp, "main");
        assertBuildSucceeded(mbp, "PR-1");
        assertThat(lastBuild(mbp, "main"), RunMatchers.logContains("content: widgets-main"));
        assertThat(lastBuild(mbp, "PR-1"), RunMatchers.logContains("content: widgets-feature-x"));
    }

    /**
     * Two branches and no open PRs: both branches are discovered, built, and each loads its own
     * branch-specific file content via SCMFileSystem.
     */
    @Test
    void twoBranchesNoPRs() throws Exception {
        mockServer
                .addRepo(OWNER, "gadgets", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "gadgets-main")
                .branch("develop", "cccc3333")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "gadgets-develop");

        WorkflowMultiBranchProject mbp = createMultiBranchProject("gadgets");
        r.waitUntilNoActivity();

        assertThat(mbp.getItems().stream().map(Item::getName).toList(), containsInAnyOrder("main", "develop"));
        assertBuildSucceeded(mbp, "main");
        assertBuildSucceeded(mbp, "develop");
        assertThat(lastBuild(mbp, "main"), RunMatchers.logContains("content: gadgets-main"));
        assertThat(lastBuild(mbp, "develop"), RunMatchers.logContains("content: gadgets-develop"));
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
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "solo-main")
                .branch("only-branch", "ffff6666")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "solo-only-branch")
                .pr(7, "only-branch", "ffff6666", "main", "aaaa1111");

        WorkflowMultiBranchProject mbp = createMultiBranchProject("solo");
        r.waitUntilNoActivity();

        var names = mbp.getItems().stream().map(Item::getName).toList();
        assertThat("PR head branch must not appear as a branch job", names, not(containsInAnyOrder("only-branch")));
        assertThat(names, containsInAnyOrder("main", "PR-7"));
        assertBuildSucceeded(mbp, "main");
        assertBuildSucceeded(mbp, "PR-7");
        assertThat(lastBuild(mbp, "main"), RunMatchers.logContains("content: solo-main"));
        assertThat(lastBuild(mbp, "PR-7"), RunMatchers.logContains("content: solo-only-branch"));
    }

    /**
     * Repo without a Jenkinsfile: no branch or PR jobs are created because the SCMProbe
     * returns NONEXISTENT for the criteria check.
     */
    @Test
    void noJenkinsfileProducesNoJobs() throws Exception {
        mockServer.addRepo(OWNER, "empty-repo", "main").branch("main", "aaaa1111");

        WorkflowMultiBranchProject mbp = createMultiBranchProject("empty-repo");
        r.waitUntilNoActivity();

        assertThat(mbp.getItems(), is(empty()));
    }

    private static void assertBuildSucceeded(WorkflowMultiBranchProject mbp, String jobName) throws Exception {
        WorkflowRun build = lastBuild(mbp, jobName);
        assertThat("job " + jobName + " result", build.getResult(), is(Result.SUCCESS));
    }

    private static WorkflowRun lastBuild(WorkflowMultiBranchProject mbp, String jobName) throws Exception {
        WorkflowJob job = mbp.getItem(jobName);
        assertThat("job " + jobName + " exists", job != null);
        WorkflowRun build = job.getLastBuild();
        assertThat("job " + jobName + " was built", build != null);
        return build;
    }

    private WorkflowMultiBranchProject createMultiBranchProject(String repoName) throws Exception {
        WorkflowMultiBranchProject mbp =
                r.jenkins.createProject(WorkflowMultiBranchProject.class, repoName + "-pipeline");
        OriginSCMSource source = new OriginSCMSource(OWNER, repoName);
        source.setCredentialsId(CREDS_ID);
        source.setTraits(List.of(new BranchDiscoveryTrait(), new PullRequestDiscoveryTrait()));
        mbp.getSourcesList().add(new BranchSource(source));
        mbp.scheduleBuild2(0).getFuture().get();
        showIndexing(mbp);
        return mbp;
    }
}
