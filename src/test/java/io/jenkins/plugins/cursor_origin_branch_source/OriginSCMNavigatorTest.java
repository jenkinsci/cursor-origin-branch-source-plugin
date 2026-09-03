package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.Item;
import jenkins.branch.NoTriggerOrganizationFolderProperty;
import jenkins.branch.OrganizationFolder;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;

class OriginSCMNavigatorTest extends MockOriginServerTestBase {

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

        OrganizationFolder folder = r.jenkins.createProject(OrganizationFolder.class, "acme");
        folder.getProperties().add(new NoTriggerOrganizationFolderProperty("^$"));
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
    }
}
