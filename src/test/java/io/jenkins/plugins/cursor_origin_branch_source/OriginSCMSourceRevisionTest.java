package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import hudson.util.StreamTaskListener;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import org.junit.jupiter.api.Test;

/** Tests for {@link OriginSCMSource#retrieveRevisions} and {@link OriginSCMSource#retrieve(String, ...)}. */
class OriginSCMSourceRevisionTest extends MockOriginServerTestBase {

    @Test
    void fetchRevisionsReturnsBranchNames() throws Exception {
        mockServer.addRepo(OWNER, "myrepo", "main").branch("main", "aaaa1111").branch("develop", "bbbb2222");

        OriginSCMSource source = sourceFor("myrepo");

        assertThat(source.fetchRevisions(StreamTaskListener.fromStderr(), null), containsInAnyOrder("main", "develop"));
    }

    @Test
    void fetchByNameResolvesViaSingleRefLookup() throws Exception {
        mockServer.addRepo(OWNER, "myrepo", "main").branch("main", "aaaa1111");

        OriginSCMSource source = sourceFor("myrepo");
        SCMRevision rev = source.fetch("main", StreamTaskListener.fromStderr(), null);

        assertThat(rev, instanceOf(AbstractGitSCMSource.SCMRevisionImpl.class));
        assertThat(((AbstractGitSCMSource.SCMRevisionImpl) rev).getHash(), is("aaaa1111"));
    }

    @Test
    void fetchByNameReturnsNullForMissingBranch() throws Exception {
        mockServer.addRepo(OWNER, "myrepo", "main").branch("main", "aaaa1111");

        OriginSCMSource source = sourceFor("myrepo");

        assertThat(source.fetch("no-such-branch", StreamTaskListener.fromStderr(), null), nullValue());
    }

    private OriginSCMSource sourceFor(String repoName) {
        OriginSCMSource source = new OriginSCMSource(OWNER, repoName);
        source.setCredentialsId(CREDS_ID);
        return source;
    }
}
