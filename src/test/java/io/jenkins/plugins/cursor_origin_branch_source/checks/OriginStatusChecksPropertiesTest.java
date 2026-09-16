package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import io.jenkins.plugins.cursor_origin_branch_source.BranchDiscoveryTrait;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class OriginStatusChecksPropertiesTest {

    private final FakeJob job = new FakeJob("widgets", "main");

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
    }

    @Test
    void ignoresTraitsThatDoNotConfigureChecks() {
        OriginSCMSource source = new OriginSCMSource("acme-corp", "widgets");
        source.setTraits(List.of(new BranchDiscoveryTrait()));

        assertThat(createProperties(source).isSkipped(job), is(false));
    }

    @Test
    void takesItsSettingsFromTheChecksTrait() {
        OriginChecksTrait trait = new OriginChecksTrait();
        trait.setSkip(true);
        OriginSCMSource source = new OriginSCMSource("acme-corp", "widgets");
        source.setTraits(List.of(new BranchDiscoveryTrait(), trait));

        OriginStatusChecksProperties properties = createProperties(source);

        assertThat(properties.getName(job), is("Jenkins"));
        assertThat(properties.isSkipped(job), is(true));
    }

    private OriginStatusChecksProperties createProperties(OriginSCMSource source) {
        return new OriginStatusChecksProperties(new FakeOriginSCMFacade().withSource(source));
    }
}
