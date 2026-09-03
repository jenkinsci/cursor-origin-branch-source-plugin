package io.jenkins.plugins.cursor_origin_branch_source;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiException;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.ListAppInstallationRepositoriesResponse;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Repo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.scm.api.SCMSourceObserver;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMTrait;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class OriginSCMNavigator extends SCMNavigator {

    private final String repoOwner;
    private String credentialsId;
    private String pattern = ".*";
    private List<SCMTrait<? extends SCMTrait<?>>> traits = defaultTraits();

    @DataBoundConstructor
    public OriginSCMNavigator(String repoOwner) {
        this.repoOwner = repoOwner;
    }

    private static List<SCMTrait<? extends SCMTrait<?>>> defaultTraits() {
        List<SCMTrait<? extends SCMTrait<?>>> t = new ArrayList<>();
        t.add(new BranchDiscoveryTrait());
        t.add(new PullRequestDiscoveryTrait());
        return t;
    }

    public String getRepoOwner() {
        return repoOwner;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public String getPattern() {
        return pattern;
    }

    @DataBoundSetter
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    @Override
    public List<SCMTrait<? extends SCMTrait<?>>> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    @Override
    @DataBoundSetter
    public void setTraits(@CheckForNull List<SCMTrait<? extends SCMTrait<?>>> traits) {
        this.traits = new ArrayList<>(traits != null ? traits : Collections.emptyList());
    }

    @NonNull
    @Override
    protected String id() {
        return CursorOriginAppCredentials.API_BASE_URI + "::" + repoOwner;
    }

    @Override
    public void visitSources(@NonNull SCMSourceObserver observer) throws IOException, InterruptedException {
        CursorOriginAppCredentials creds = CredentialsProvider.findCredentialByIdInItem(
                credentialsId, CursorOriginAppCredentials.class, observer.getContext(), ACL.SYSTEM2, null);
        if (creds == null) {
            throw new IOException("No credentials found with id: " + credentialsId);
        }

        TaskListener listener = observer.getListener();
        OriginServiceApi api = CursorOriginAppCredentials.apiWithToken(creds.mintToken());
        Pattern namePattern = Pattern.compile(pattern);

        List<SCMSourceTrait> sourceTraits = new ArrayList<>();
        for (SCMTrait<?> t : traits) {
            if (t instanceof SCMSourceTrait st) {
                sourceTraits.add(st);
            }
        }

        try {
            String pageToken = null;
            do {
                ListAppInstallationRepositoriesResponse resp =
                        api.originServiceListAppInstallationRepositories(null, pageToken);
                for (Repo repo : resp.getRepositories()) {
                    String ownerSlug = repo.getOwner() != null ? repo.getOwner().getSlug() : null;
                    if (!repoOwner.equals(ownerSlug)) {
                        continue;
                    }
                    if (!namePattern.matcher(repo.getName()).matches()) {
                        continue;
                    }
                    listener.getLogger().println("Found repo: " + repoOwner + "/" + repo.getName());
                    SCMSourceObserver.ProjectObserver projectObserver = observer.observe(repo.getName());
                    OriginSCMSource source = new OriginSCMSource(repoOwner, repo.getName());
                    source.setCredentialsId(credentialsId);
                    source.setTraits(sourceTraits);
                    projectObserver.addSource(source);
                    projectObserver.complete();
                }
                pageToken = resp.getNextPageToken();
            } while (pageToken != null && !pageToken.isEmpty());
        } catch (ApiException e) {
            throw new IOException("Origin API error during navigator scan", e);
        }
    }

    @Extension
    @Symbol("cursorOrigin")
    public static class DescriptorImpl extends SCMNavigatorDescriptor {

        @Override
        public String getDisplayName() {
            return "Cursor Origin";
        }

        @Override
        public SCMNavigator newInstance(String name) {
            OriginSCMNavigator nav = new OriginSCMNavigator(name);
            nav.setTraits(defaultTraits());
            return nav;
        }

        @POST
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
