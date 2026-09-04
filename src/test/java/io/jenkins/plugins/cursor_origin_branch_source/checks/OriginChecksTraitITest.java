package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.util.FormValidation;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.api.ChecksPublisherFactory;
import io.jenkins.plugins.checks.status.AbstractStatusChecksProperties;
import io.jenkins.plugins.cursor_origin_branch_source.BranchDiscoveryTrait;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.util.List;
import jenkins.branch.BranchSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OriginChecksTraitITest {

    @Test
    void registersTheChecksExtensions(JenkinsRule r) {
        assertThat(
                ExtensionList.lookup(ChecksPublisherFactory.class),
                hasItem(instanceOf(OriginChecksPublisherFactory.class)));
        assertThat(
                ExtensionList.lookup(AbstractStatusChecksProperties.class),
                hasItem(instanceOf(OriginStatusChecksProperties.class)));
    }

    /** The trait must be offered on Cursor Origin sources so that its settings can be configured. */
    @Test
    void isOfferedAsATraitOfAnOriginSource(JenkinsRule r) {
        List<SCMSourceTraitDescriptor> descriptors = new OriginSCMSource.DescriptorImpl().getTraitDescriptors();

        assertThat(
                descriptors.stream()
                        .filter(OriginChecksTrait.DescriptorImpl.class::isInstance)
                        .toList(),
                hasSize(1));
    }

    @Test
    void survivesAConfigurationRoundTrip(JenkinsRule r) throws Exception {
        WorkflowMultiBranchProject project = r.jenkins.createProject(WorkflowMultiBranchProject.class, "widgets");
        OriginChecksTrait trait = new OriginChecksTrait();
        trait.setName("continuous-integration/jenkins");
        trait.setSkip(true);
        trait.setUnstableBuildNeutral(true);
        trait.setSuppressLogs(true);
        trait.setSkipProgressUpdates(true);
        trait.setVerboseConsoleLog(true);
        OriginSCMSource source = new OriginSCMSource("acme-corp", "widgets");
        source.setCredentialsId("origin-creds");
        source.setTraits(List.of(new BranchDiscoveryTrait(), trait));
        project.getSourcesList().add(new BranchSource(source));

        r.configRoundtrip(project);

        OriginSCMSource reloaded =
                (OriginSCMSource) project.getSourcesList().get(0).getSource();
        List<SCMSourceTrait> traits = reloaded.getTraits();
        OriginChecksTrait reloadedTrait = traits.stream()
                .filter(OriginChecksTrait.class::isInstance)
                .map(OriginChecksTrait.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(reloadedTrait.getName(), is("continuous-integration/jenkins"));
        assertThat(reloadedTrait.isSkip(), is(true));
        assertThat(reloadedTrait.isUnstableBuildNeutral(), is(true));
        assertThat(reloadedTrait.isSuppressLogs(), is(true));
        assertThat(reloadedTrait.isSkipProgressUpdates(), is(true));
        assertThat(reloadedTrait.isVerboseConsoleLog(), is(true));
    }

    @Test
    void rejectsABlankCheckName(JenkinsRule r) {
        OriginChecksTrait.DescriptorImpl descriptor =
                ExtensionList.lookupSingleton(OriginChecksTrait.DescriptorImpl.class);

        assertThat(descriptor.doCheckName("Jenkins").kind, is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckName(" ").kind, is(FormValidation.Kind.ERROR));
    }

    /**
     * The checks API asks every factory in turn; a job that has nothing to do with Cursor Origin must
     * be left to the other providers.
     */
    @Test
    void doesNotClaimJobsThatAreNotOriginBacked(JenkinsRule r) throws Exception {
        FreeStyleProject project = r.createFreeStyleProject("freestyle");

        ChecksPublisher publisher = ChecksPublisherFactory.fromJob(project, r.createTaskListener());

        assertThat(publisher, is(instanceOf(ChecksPublisher.NullChecksPublisher.class)));
    }
}
