package io.jenkins.plugins.cursor_origin_branch_source;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiException;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Branch;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.ListBranchesResponse;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.ListPullRequestsResponse;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.PullRequest;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Repo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitRemoteHeadRefAction;
import jenkins.plugins.git.GitSCMBuilder;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceEvent;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class OriginSCMSource extends AbstractGitSCMSource {

    private static final Logger LOGGER = Logger.getLogger(OriginSCMSource.class.getName());

    private final String repoOwner;
    private final String repository;
    private String credentialsId;
    private List<SCMSourceTrait> traits = new ArrayList<>();

    @DataBoundConstructor
    public OriginSCMSource(String repoOwner, String repository) {
        this.repoOwner = repoOwner;
        this.repository = repository;
    }

    public String getRepoOwner() {
        return repoOwner;
    }

    public String getRepository() {
        return repository;
    }

    @Override
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @Override
    public String getRemote() {
        return "https://origin.cursor.com/" + repoOwner + "/" + repository + ".git";
    }

    @NonNull
    @Override
    public List<SCMSourceTrait> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    @DataBoundSetter
    public void setTraits(@CheckForNull List<SCMSourceTrait> traits) {
        this.traits = new ArrayList<>(traits != null ? traits : Collections.emptyList());
    }

    private CursorOriginAppCredentials lookupCredentials() {
        return CredentialsProvider.findCredentialByIdInItem(
                credentialsId, CursorOriginAppCredentials.class, getOwner(), ACL.SYSTEM2, null);
    }

    @Override
    protected void retrieve(
            @CheckForNull SCMSourceCriteria criteria,
            @NonNull SCMHeadObserver observer,
            @CheckForNull SCMHeadEvent<?> event,
            @NonNull TaskListener listener)
            throws IOException, InterruptedException {

        CursorOriginAppCredentials creds = lookupCredentials();
        if (creds == null) {
            throw new IOException("No credentials found with id: " + credentialsId);
        }
        listener.getLogger()
                .println("Connecting to Cursor Origin using app credentials: " + CredentialsNameProvider.name(creds));
        OriginServiceApi api = CursorOriginAppCredentials.apiWithToken(creds.mintToken());

        try (OriginSCMSourceRequest request = new OriginSCMSourceContext(criteria, observer)
                .withTraits(traits)
                .newRequest(this, listener)) {

            List<PullRequest> openPRs = Collections.emptyList();
            if (request.isFetchPRs()) {
                ListPullRequestsResponse prResp =
                        api.originServiceListPullRequests(repoOwner, repository, null, "open", null, null);
                openPRs = prResp.getPullRequests();
                Set<String> prHeadBranches = new HashSet<>();
                for (PullRequest pr : openPRs) {
                    prHeadBranches.add(pr.getHead().getRef());
                }
                request.setPRHeadBranches(prHeadBranches);
            }

            if (request.isFetchBranches()) {
                listener.getLogger().println("Fetching branches from " + repoOwner + "/" + repository);
                ListBranchesResponse resp = api.originServiceListBranches(repoOwner, repository, null, null);
                for (Branch branch : resp.getBranches()) {
                    if (request.getPRHeadBranches().contains(branch.getName())) {
                        listener.getLogger()
                                .format("Ignoring branch %s: head of an open pull request%n", branch.getName());
                        LOGGER.fine(() -> "Skipping branch " + branch.getName() + " (PR head)");
                        continue;
                    }
                    SCMHead head = new SCMHead(branch.getName());
                    SCMRevision rev = new AbstractGitSCMSource.SCMRevisionImpl(
                            head, branch.getCommit().getSha());
                    if (request.process(
                            head,
                            rev,
                            (h, r) -> new OriginSCMProbe(h.getName(), api, repoOwner, repository, h.getName()))) {
                        return;
                    }
                }
            }

            if (request.isFetchPRs()) {
                listener.getLogger().println("Fetching pull requests from " + repoOwner + "/" + repository);
                for (PullRequest pr : openPRs) {
                    OriginPullRequestSCMHead head = new OriginPullRequestSCMHead(
                            pr.getNumber(), pr.getHead().getRef(), pr.getBase().getRef());
                    OriginPullRequestSCMRevision rev = new OriginPullRequestSCMRevision(
                            head, pr.getHead().getSha(), pr.getBase().getSha());
                    if (request.process(
                            head,
                            rev,
                            (h, r) -> new OriginSCMProbe(h.getName(), api, repoOwner, repository, h.getHeadBranch()))) {
                        return;
                    }
                }
            }
        } catch (ApiException e) {
            throw new IOException("Origin API error during source scan", e);
        }
    }

    @Override
    protected SCMRevision retrieve(@NonNull SCMHead head, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        CursorOriginAppCredentials creds = lookupCredentials();
        if (creds == null) {
            throw new IOException("No credentials found with id: " + credentialsId);
        }
        OriginServiceApi api = CursorOriginAppCredentials.apiWithToken(creds.mintToken());
        try {
            if (head instanceof OriginPullRequestSCMHead prHead) {
                ListPullRequestsResponse resp =
                        api.originServiceListPullRequests(repoOwner, repository, null, "open", null, null);
                for (PullRequest pr : resp.getPullRequests()) {
                    if (prHead.getNumber().equals(pr.getNumber())) {
                        return new OriginPullRequestSCMRevision(
                                prHead, pr.getHead().getSha(), pr.getBase().getSha());
                    }
                }
                return null; // PR closed
            } else {
                ListBranchesResponse resp = api.originServiceListBranches(repoOwner, repository, null, null);
                for (Branch branch : resp.getBranches()) {
                    if (head.getName().equals(branch.getName())) {
                        return new AbstractGitSCMSource.SCMRevisionImpl(
                                head, branch.getCommit().getSha());
                    }
                }
                return null; // branch deleted
            }
        } catch (ApiException e) {
            throw new IOException("Origin API error retrieving revision for " + head.getName(), e);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    protected List<Action> retrieveActions(@CheckForNull SCMSourceEvent event, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        CursorOriginAppCredentials creds = lookupCredentials();
        if (creds == null) {
            return Collections.emptyList();
        }
        try {
            OriginServiceApi api = CursorOriginAppCredentials.apiWithToken(creds.mintToken());
            Repo repo = api.originServiceGetRepo(repoOwner, repository);
            String defaultBranch = repo.getDefaultBranch();
            if (defaultBranch != null && !defaultBranch.isBlank()) {
                return List.of(new GitRemoteHeadRefAction(getRemote(), defaultBranch));
            }
        } catch (ApiException e) {
            listener.getLogger().println("Could not retrieve default branch via API: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Failed to retrieve default branch for " + repoOwner + "/" + repository, e);
        }
        return Collections.emptyList();
    }

    @Override
    protected GitSCMBuilder<?> newBuilder(@NonNull SCMHead head, @CheckForNull SCMRevision revision) {
        if (head instanceof OriginPullRequestSCMHead prHead) {
            SCMRevision gitRevision = revision instanceof OriginPullRequestSCMRevision prRev
                    ? new AbstractGitSCMSource.SCMRevisionImpl(head, prRev.getHeadHash())
                    : revision;
            return new GitSCMBuilder<>(head, gitRevision, getRemote(), getCredentialsId())
                    .withRefSpec("+refs/heads/" + prHead.getHeadBranch() + ":refs/remotes/@{remote}/" + head.getName());
        }
        return super.newBuilder(head, revision);
    }

    @Extension
    @Symbol("cursorOrigin")
    public static class DescriptorImpl extends SCMSourceDescriptor {

        @Override
        public String getDisplayName() {
            return "Cursor Origin";
        }

        public List<SCMSourceTraitDescriptor> getTraitDescriptors() {
            return SCMSourceTrait._for(this, OriginSCMSourceContext.class, null);
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialsId);
                }
            }
            return result.includeMatchingAs(
                            ACL.SYSTEM2,
                            item,
                            CursorOriginAppCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
