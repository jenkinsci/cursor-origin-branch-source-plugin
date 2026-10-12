package io.jenkins.plugins.cursor_origin_branch_source.checks;

import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A {@link Job} that needs no running Jenkins, for tests of the checks code that only read a job's
 * identity.
 *
 * <p>{@code getFullName()} and {@code getFullDisplayName()} are final on {@code AbstractItem} and
 * derive from the parent, so the name and the enclosing group are what shape them: a job named
 * {@code main} inside a group named {@code widgets} reports {@code widgets/main} and
 * {@code widgets » main}, exactly as a branch of a multibranch project would.
 *
 * <p>Everything the code under test does not use throws {@link UnsupportedOperationException} rather
 * than returning a default, so a new dependency on the Jenkins model shows up as a loud failure
 * instead of a silently wrong value.
 */
class FakeJob extends Job<FakeJob, FakeRun> {

    /** A job named {@code name} inside a group named {@code groupName}. */
    FakeJob(String groupName, String name) {
        super(new FakeItemGroup(groupName), name);
    }

    @Override
    public boolean isBuildable() {
        return false;
    }

    @Override
    protected SortedMap<Integer, ? extends FakeRun> _getRuns() {
        return new TreeMap<>();
    }

    @Override
    protected void removeRun(FakeRun run) {
        throw new UnsupportedOperationException();
    }

    /** The enclosing group, which is all that {@code getFullName()} needs to resolve. */
    private static final class FakeItemGroup implements ItemGroup<Item> {

        private final String name;

        FakeItemGroup(String name) {
            this.name = name;
        }

        @Override
        public String getFullName() {
            return name;
        }

        @Override
        public String getFullDisplayName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public Collection<Item> getItems() {
            return List.of();
        }

        @Override
        public String getUrl() {
            return "job/" + name + "/";
        }

        @Override
        public String getUrlChildPrefix() {
            return "job";
        }

        @Override
        public Item getItem(String itemName) {
            return null;
        }

        @Override
        public File getRootDir() {
            throw new UnsupportedOperationException();
        }

        @Override
        public File getRootDirFor(Item child) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onRenamed(Item item, String oldName, String newName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onDeleted(Item item) {
            throw new UnsupportedOperationException();
        }
    }
}
