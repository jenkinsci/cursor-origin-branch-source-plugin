package io.jenkins.plugins.cursor_origin_branch_source;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiException;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.Content;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import jenkins.scm.api.SCMFile;

/** Represents a file or directory in a Cursor Origin repository. */
class OriginSCMFile extends SCMFile {

    private final OriginServiceApi api;
    private final String owner;
    private final String repo;
    private final String ref;

    /** Constructs the root directory. */
    OriginSCMFile(OriginServiceApi api, String owner, String repo, String ref) {
        super();
        this.api = api;
        this.owner = owner;
        this.repo = repo;
        this.ref = ref;
        type(Type.DIRECTORY);
    }

    private OriginSCMFile(@NonNull OriginSCMFile parent, String name) {
        super(parent, name);
        this.api = parent.api;
        this.owner = parent.owner;
        this.repo = parent.repo;
        this.ref = parent.ref;
    }

    @NonNull
    @Override
    protected SCMFile newChild(@NonNull String name, boolean assumeIsDirectory) {
        OriginSCMFile child = new OriginSCMFile(this, name);
        if (assumeIsDirectory) {
            child.type(Type.DIRECTORY);
        }
        return child;
    }

    @NonNull
    @Override
    public Iterable<SCMFile> children() throws IOException, InterruptedException {
        try {
            Content content = api.originServiceGetContents(owner, repo, getPath(), ref);
            List<SCMFile> result = new ArrayList<>();
            if (content != null && content.getEntries() != null) {
                for (Content entry : content.getEntries()) {
                    OriginSCMFile child = new OriginSCMFile(this, entry.getName());
                    child.type("dir".equals(entry.getType()) ? Type.DIRECTORY : Type.REGULAR_FILE);
                    result.add(child);
                }
            }
            return result;
        } catch (ApiException e) {
            throw new IOException("Failed to list contents of " + getPath(), e);
        }
    }

    @Override
    public long lastModified() {
        return 0;
    }

    @NonNull
    @Override
    protected Type type() throws IOException, InterruptedException {
        try {
            Content content = api.originServiceGetContents(owner, repo, getPath(), ref);
            if (content == null) {
                return Type.NONEXISTENT;
            }
            return switch (content.getType()) {
                case "file" -> Type.REGULAR_FILE;
                case "dir" -> Type.DIRECTORY;
                default -> Type.OTHER;
            };
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return Type.NONEXISTENT;
            }
            throw new IOException("Failed to stat " + getPath(), e);
        }
    }

    @NonNull
    @Override
    public InputStream content() throws IOException, InterruptedException {
        try {
            Content content = api.originServiceGetContents(owner, repo, getPath(), ref);
            if (content == null || !"file".equals(content.getType())) {
                throw new IOException("Not a file: " + getPath());
            }
            String raw = content.getContent();
            if (raw == null) {
                return new ByteArrayInputStream(new byte[0]);
            }
            if ("base64".equals(content.getEncoding())) {
                return new ByteArrayInputStream(Base64.getMimeDecoder().decode(raw));
            }
            return new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                throw new IOException("File not found: " + getPath(), e);
            }
            throw new IOException("Failed to read " + getPath(), e);
        }
    }
}
