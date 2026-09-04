package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.model.Job;
import io.jenkins.plugins.cursor_origin_branch_source.BranchDiscoveryTrait;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@SuppressWarnings("rawtypes")
class OriginStatusChecksPropertiesTest {

    private final Job job = mock(Job.class);

    @Test
    void appliesToOriginBackedJobsOnly() {
        assertThat(createProperties(new OriginSCMSource("acme-corp", "widgets")).isApplicable(job), is(true));
        assertThat(createProperties(null).isApplicable(job), is(false));
    }

    /** Checks are reported without any configuration; the trait only overrides the defaults. */
    @Test
    void reportsTheBuildStatusAsJenkinsByDefault() {
        OriginStatusChecksProperties properties = createProperties(new OriginSCMSource("acme-corp", "widgets"));

        assertThat(properties.getName(job), is("Jenkins"));
        assertThat(properties.isSkipped(job), is(false));
        assertThat(properties.isUnstableBuildNeutral(job), is(false));
        assertThat(properties.isSuppressLogs(job), is(false));
        assertThat(properties.isSkipProgressUpdates(job), is(false));
    }

    @Test
    void ignoresTraitsThatDoNotConfigureChecks() {
        OriginSCMSource source = new OriginSCMSource("acme-corp", "widgets");
        source.setTraits(List.of(new BranchDiscoveryTrait()));

        assertThat(createProperties(source).getName(job), is("Jenkins"));
    }

    @Test
    void takesItsSettingsFromTheChecksTrait() {
        OriginChecksTrait trait = new OriginChecksTrait();
        trait.setName("continuous-integration/jenkins");
        trait.setSkip(true);
        trait.setUnstableBuildNeutral(true);
        trait.setSuppressLogs(true);
        trait.setSkipProgressUpdates(true);
        OriginSCMSource source = new OriginSCMSource("acme-corp", "widgets");
        source.setTraits(List.of(new BranchDiscoveryTrait(), trait));

        OriginStatusChecksProperties properties = createProperties(source);

        assertThat(properties.getName(job), is("continuous-integration/jenkins"));
        assertThat(properties.isSkipped(job), is(true));
        assertThat(properties.isUnstableBuildNeutral(job), is(true));
        assertThat(properties.isSuppressLogs(job), is(true));
        assertThat(properties.isSkipProgressUpdates(job), is(true));
    }

    private OriginStatusChecksProperties createProperties(OriginSCMSource source) {
        OriginSCMFacade facade = mock(OriginSCMFacade.class);
        when(facade.findOriginSCMSource(job)).thenReturn(Optional.ofNullable(source));
        return new OriginStatusChecksProperties(facade);
    }
}
