package io.jenkins.plugins.cursor_origin_branch_source;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiException;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Content;
import java.io.IOException;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMProbe;
import jenkins.scm.api.SCMProbeStat;

/** Checks file existence in an Origin repo via the REST API without cloning. */
class OriginSCMProbe extends SCMProbe {

    private final String name;
    private final OriginServiceApi api;
    private final String owner;
    private final String repo;
    /** Branch name used as the git ref for file lookups. */
    private final String ref;

    OriginSCMProbe(String name, OriginServiceApi api, String owner, String repo, String ref) {
        this.name = name;
        this.api = api;
        this.owner = owner;
        this.repo = repo;
        this.ref = ref;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long lastModified() {
        return 0;
    }

    @NonNull
    @Override
    public SCMProbeStat stat(@NonNull String path) throws IOException {
        try {
            Content content = api.originServiceGetContents(owner, repo, path, ref);
            if (content == null) {
                return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
            }
            return switch (content.getType()) {
                case "file" -> SCMProbeStat.fromType(SCMFile.Type.REGULAR_FILE);
                case "dir" -> SCMProbeStat.fromType(SCMFile.Type.DIRECTORY);
                default -> SCMProbeStat.fromType(SCMFile.Type.OTHER);
            };
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
            }
            throw new IOException("Failed to stat " + path + " in " + owner + "/" + repo + "@" + ref, e);
        }
    }

    @Override
    public void close() {}
}
