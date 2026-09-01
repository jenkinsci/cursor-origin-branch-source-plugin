package io.jenkins.plugins.cursor_origin_branch_source;

import com.cloudbees.plugins.credentials.CredentialsSnapshotTaker;
import hudson.Extension;

/**
 * Prevents {@code UsernamePasswordCredentialsSnapshotTaker} from freezing the ephemeral
 * installation token into a static credential, which would break subsequent token refreshes.
 */
@Extension
public class CursorOriginAppCredentialsSnapshotTaker extends CredentialsSnapshotTaker<CursorOriginAppCredentials> {

    @Override
    public Class<CursorOriginAppCredentials> type() {
        return CursorOriginAppCredentials.class;
    }

    @Override
    public CursorOriginAppCredentials snapshot(CursorOriginAppCredentials credentials) {
        return credentials;
    }
}
