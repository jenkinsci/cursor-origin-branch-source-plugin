package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.Item;
import hudson.model.Result;
import jenkins.branch.OrganizationFolder;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;

class OriginSCMNavigatorTest extends MockOriginServerTestBase {

    private static final String JENKINSFILE = "echo \"loading: ${readTrusted('some-file')}\"";
    private static final String SOME_FILE = "hello from origin";

    /**
     * OrganizationFolder scanning two repos: navigator discovers both, creates one multibranch
     * project per repo, and builds succeed via SCMFileSystem lightweight checkout.
     */
    @Test
    void organizationFolderDiscoversTwoRepos() throws Exception {
        mockServer
                .addRepo(OWNER, "alpha", "main")
                .branch("main", "aaaa1111")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", SOME_FILE);
        mockServer
                .addRepo(OWNER, "beta", "main")
                .branch("main", "dddd4444")
                .branch("hotfix", "eeee5555")
                .file("Jenkinsfile", JENKINSFILE)
                .file("some-file", SOME_FILE)
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
    }
}
