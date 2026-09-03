package io.jenkins.plugins.cursor_origin_branch_source;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.security.ACL;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import java.io.IOException;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;

/** {@link SCMFileSystem} for Cursor Origin, enabling lightweight checkout of {@code Jenkinsfile}. */
class OriginSCMFileSystem extends SCMFileSystem {

    private final OriginServiceApi api;
    private final String owner;
    private final String repo;
    private final String ref;

    private OriginSCMFileSystem(
            OriginServiceApi api, String owner, String repo, String ref, @CheckForNull SCMRevision rev) {
        super(rev);
        this.api = api;
        this.owner = owner;
        this.repo = repo;
        this.ref = ref;
    }

    @Override
    public void close() {}

    @Override
    public long lastModified() {
        return 0;
    }

    @NonNull
    @Override
    public SCMFile getRoot() {
        return new OriginSCMFile(api, owner, repo, ref);
    }

    @Extension
    public static class BuilderImpl extends SCMFileSystem.Builder {

        @Override
        public boolean supports(SCM source) {
            return false;
        }

        @Override
        @SuppressWarnings("rawtypes")
        protected boolean supportsDescriptor(SCMDescriptor scmDescriptor) {
            return false;
        }

        @Override
        public boolean supports(SCMSource source) {
            return source instanceof OriginSCMSource;
        }

        @Override
        protected boolean supportsDescriptor(SCMSourceDescriptor descriptor) {
            return descriptor instanceof OriginSCMSource.DescriptorImpl;
        }

        @Override
        public SCMFileSystem build(@NonNull Item owner, @NonNull SCM scm, @CheckForNull SCMRevision rev) {
            return null;
        }

        @Override
        public SCMFileSystem build(@NonNull SCMSource source, @NonNull SCMHead head, @CheckForNull SCMRevision rev)
                throws IOException, InterruptedException {
            OriginSCMSource src = (OriginSCMSource) source;
            CursorOriginAppCredentials creds = CredentialsProvider.findCredentialByIdInItem(
                    src.getCredentialsId(), CursorOriginAppCredentials.class, src.getOwner(), ACL.SYSTEM2, null);
            if (creds == null) {
                return null;
            }
            OriginServiceApi api = CursorOriginAppCredentials.apiWithToken(creds.mintToken());

            String ref;
            if (rev instanceof OriginPullRequestSCMRevision prRev) {
                ref = prRev.getHeadHash();
            } else if (rev instanceof AbstractGitSCMSource.SCMRevisionImpl gitRev) {
                ref = gitRev.getHash();
            } else if (head instanceof OriginPullRequestSCMHead prHead) {
                ref = prHead.getHeadBranch();
            } else {
                ref = head.getName();
            }
            return new OriginSCMFileSystem(api, src.getRepoOwner(), src.getRepository(), ref, rev);
        }
    }
}
