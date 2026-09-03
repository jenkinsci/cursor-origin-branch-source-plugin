package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.Item;
import hudson.model.Result;
import jenkins.branch.OrganizationFolder;
import jenkins.test.RunMatchers;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;

class OriginSCMNavigatorTest extends MockOriginServerTestBase {

    private static final String JENKINSFILE = "echo \"content: ${readTrusted('some-file')}\"";

    /**
     * OrganizationFolder scanning two repos: navigator discovers both, creates one multibranch
     * project per repo, and builds succeed with branch-specific content via SCMFileSystem.
     */
    @Test
    void organizationFolderDiscoversTwoRepos() throws Exception {
        mockServer
                .addRepo(OWNER, "alpha", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "alpha-main");
        mockServer
                .addRepo(OWNER, "beta", "main")
                .branch("main", "dddd4444")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "beta-main")
                .branch("hotfix", "eeee5555")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", "beta-hotfix")
                .pr(3, "hotfix", "eeee5555", "main", "dddd4444");

        OrganizationFolder folder = r.jenkins.createProject(OrganizationFolder.class, "acme");
        OriginSCMNavigator navigator = new OriginSCMNavigator(OWNER);
        navigator.setCredentialsId(CREDS_ID);
        folder.getNavigators().add(navigator);
        folder.scheduleBuild2(0).getFuture().get();
        showIndexing(folder);
        r.waitUntilNoActivity();

        var projects = folder.getItems();
        assertThat(projects, hasSize(2));
        assertThat(projects.stream().map(Item::getName).toList(), containsInAnyOrder("alpha", "beta"));

        WorkflowMultiBranchProject betaMbp = (WorkflowMultiBranchProject) folder.getItem("beta");
        assertNotNull(betaMbp);
        assertThat(betaMbp.getItems().stream().map(Item::getName).toList(), containsInAnyOrder("main", "PR-3"));

        WorkflowJob betaMain = betaMbp.getItem("main");
        assertNotNull(betaMain);
        WorkflowRun betaMainBuild = betaMain.getLastBuild();
        assertNotNull(betaMainBuild);
        assertThat(betaMainBuild.getResult(), is(Result.SUCCESS));
        assertThat(betaMainBuild, RunMatchers.logContains("content: beta-main"));

        WorkflowJob betaPR3 = betaMbp.getItem("PR-3");
        assertNotNull(betaPR3);
        WorkflowRun betaPR3Build = betaPR3.getLastBuild();
        assertNotNull(betaPR3Build);
        assertThat(betaPR3Build.getResult(), is(Result.SUCCESS));
        assertThat(betaPR3Build, RunMatchers.logContains("content: beta-hotfix"));
    }
}
