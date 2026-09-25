package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Cause;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * Cause that is applied when a check rerun is requested from Origin
 */
public class OriginCheckRerunCause extends Cause {

    private final int originalBuildNumber;
    private transient Run<?, ?> run;

    protected OriginCheckRerunCause(Run<?, ?> rerunBuild) {
        originalBuildNumber = rerunBuild.getNumber();
    }

    @Override
    public String getShortDescription() {
        return Messages.checkRerunCause_shortDescription();
    }

    public int getOriginalBuildNumber() {
        return originalBuildNumber;
    }

    @CheckForNull
    public Run<?, ?> getOriginalRun() {
        return run.getParent().getBuildByNumber(originalBuildNumber);
    }

    @Override
    public void onAddedTo(Run build) {
        this.run = build;
    }

    @Override
    public void onLoad(Run<?, ?> build) {
        this.run = build;
    }

    @Override
    public void print(TaskListener listener) {
        listener.getLogger().println("Re-run requested by Origin webhook");
    }

    public static class OriginCheckRerunUserCause extends OriginCheckRerunCause {

        private final String id;
        private final String email;

        @CheckForNull
        private final String displayName;

        OriginCheckRerunUserCause(Run<?, ?> rebuildOf, String id, String email, @Nullable String displayName) {
            super(rebuildOf);
            this.id = id;
            this.email = email;
            this.displayName = displayName;
        }

        public String getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        @CheckForNull
        public String getDisplayName() {
            return displayName;
        }
    }

    public static class OriginCheckRerunAppCause extends OriginCheckRerunCause {
        private final String namespace;
        private final String appId;

        @CheckForNull
        private final String displayName;

        OriginCheckRerunAppCause(Run<?, ?> rebuildOf, String namespace, String appId, String displayName) {
            super(rebuildOf);
            this.namespace = namespace;
            this.appId = appId;
            this.displayName = displayName;
        }

        public String getAppId() {
            return appId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getNamespace() {
            return namespace;
        }
    }

    public static class OriginCheckRerunServiceAccountCause extends OriginCheckRerunCause {
        private final String id;

        OriginCheckRerunServiceAccountCause(Run<?, ?> rebuildOf, String id) {
            super(rebuildOf);
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }
}
