package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Cause;
import hudson.model.TaskListener;

/**
 * Cause that is applied when a check rerun is requested from Origin
 */
public class OriginCheckRerunCause extends Cause {

    protected OriginCheckRerunCause() {}

    @Override
    public String getShortDescription() {
        return Messages.checkRerunCause_shortDescription();
    }

    @Override
    public void print(TaskListener listener) {
        listener.getLogger().println("Re-run requested by origin webhook");
    }

    public static class OriginCheckRerunUserCause extends OriginCheckRerunCause {

        private final String id;
        private final String email;

        @CheckForNull
        private final String displayName;

        OriginCheckRerunUserCause(String id, String email, @Nullable String displayName) {
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

        OriginCheckRerunAppCause(String namespace, String appId, String displayName) {
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

        public String getNamesapce() {
            return namespace;
        }
    }

    public static class OriginCheckRerunServiceAccountCause extends OriginCheckRerunCause {
        private final String id;

        OriginCheckRerunServiceAccountCause(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }
}
