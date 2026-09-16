package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.model.TaskListener;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.cursor_origin_branch_source.OriginAppCredentials;
import io.jenkins.plugins.cursor_origin_branch_source.OriginPullRequestSCMHead;
import io.jenkins.plugins.cursor_origin_branch_source.OriginPullRequestSCMRevision;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import org.junit.jupiter.api.Test;

@SuppressWarnings("rawtypes")
class OriginChecksContextTest {

    private static final String OWNER = "acme-corp";
    private static final String REPOSITORY = "widgets";
    private static final String CREDENTIALS_ID = "origin-creds";
    private static final String BRANCH_SHA = "9a41f0c3d2b8e7f6a5c4d3e2f1b0a9c8d7e6f5a4";
    private static final String PR_HEAD_SHA = "1111111111111111111111111111111111111111";
    private static final String PR_BASE_SHA = "2222222222222222222222222222222222222222";
    private static final String URL = "http://localhost:8080/job/widgets/job/main/3/";

    /** used by {@link #listener()} and {@link #buildLog()} */
    private ByteArrayOutputStream console;

    @Test
    void resolvesRepositoryCoordinatesFromTheSource() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        OriginSCMSource source = createSource();
        FakeOriginSCMFacade facade = facadeWithSource(source);

        OriginChecksContext context = OriginChecksContext.fromRun(run, URL, facade);

        assertThat(context.getRepoOwner(), is(OWNER));
        assertThat(context.getRepository(), is(REPOSITORY));
        assertThat(context.getUrl(), is(URL));
        assertThat(context.getJob(), is(job));
        assertThat(context.getRun(), is(run));
    }

    @Test
    void readsTheShaOfABranchBuildFromTheRun() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        OriginSCMSource source = createSource();
        SCMHead head = new SCMHead("main");
        SCMRevision revision = new AbstractGitSCMSource.SCMRevisionImpl(head, BRANCH_SHA);

        FakeOriginSCMFacade facade = facadeWithSource(source).withRevision(revision);

        assertThat(OriginChecksContext.fromRun(run, URL, facade).getHeadSha(), is(BRANCH_SHA));
    }

    /**
     * A check reported against the ephemeral merge commit would not surface on the pull request, so
     * the head of the pull request branch is what has to be reported.
     */
    @Test
    void reportsAPullRequestAgainstItsHeadSha() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        OriginSCMSource source = createSource();
        OriginPullRequestSCMHead head = new OriginPullRequestSCMHead("7", "feature-x", "main");
        SCMRevision revision = new OriginPullRequestSCMRevision(head, PR_HEAD_SHA, PR_BASE_SHA);

        FakeOriginSCMFacade facade = facadeWithSource(source).withRevision(revision);

        assertThat(OriginChecksContext.fromRun(run, URL, facade).getHeadSha(), is(PR_HEAD_SHA));
    }

    @Test
    void unresolvableShaIsReportedRatherThanGuessed() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = facadeWithSource(createSource());

        OriginChecksContext context = OriginChecksContext.fromRun(run, URL, facade);

        IllegalStateException e = assertThrows(IllegalStateException.class, context::getHeadSha);
        assertThat(e.getMessage(), is("No SHA found for job: widgets/main"));
    }

    @Test
    void identifiesEachBuildAsItsOwnAttempt() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = facadeWithSource(createSource());

        assertThat(OriginChecksContext.fromRun(run, URL, facade).getExternalId(), is("widgets/main#3"));
    }

    /**
     * The suite key is what required-check configuration is keyed on, so it must not encode the branch
     * or the build; without an SCM source owner the job itself is the best available fallback.
     */
    @Test
    void groupsChecksOfAllBranchesIntoOneSuite() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = facadeWithSource(createSource());

        OriginChecksContext context = OriginChecksContext.fromRun(run, URL, facade);

        assertThat(context.getSuiteKey(), is("widgets/main"));
        assertThat(context.getSuiteName(), is("widgets » main"));
    }

    /**
     * The checks API asks every factory about every build, so a build that has nothing to do with
     * Cursor Origin must be declined without a word in its log.
     */
    @Test
    void isNotValidAndSaysNothingForAJobWithoutAnOriginSource() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = new FakeOriginSCMFacade();

        assertThat(OriginChecksContext.fromRun(run, URL, facade).isValid(listener()), is(false));
        assertThat(buildLog(), is(emptyString()));
    }

    @Test
    void isNotValidWithoutConfiguredCredentials() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        OriginSCMSource source = new OriginSCMSource(OWNER, REPOSITORY);
        FakeOriginSCMFacade facade = facadeWithSource(source);

        assertThat(OriginChecksContext.fromRun(run, URL, facade).isValid(listener()), is(false));
        assertThat(buildLog(), containsString("No credentials configured on the Cursor Origin SCM source"));
    }

    @Test
    void isNotValidWhenTheConfiguredCredentialsAreMissing() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = facadeWithSource(createSource());

        assertThat(OriginChecksContext.fromRun(run, URL, facade).isValid(listener()), is(false));
        assertThat(buildLog(), containsString("No Cursor Origin app credentials found with id: 'origin-creds'"));
    }

    @Test
    void isNotValidWithoutAResolvableSha() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = facadeWithSource(createSource());
        facade.withCredentials(someCredentials());

        assertThat(OriginChecksContext.fromRun(run, URL, facade).isValid(listener()), is(false));
        assertThat(buildLog(), containsString("No HEAD SHA found for acme-corp/widgets"));
    }

    @Test
    void isValidForAFullyConfiguredOriginJob() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        OriginSCMSource source = createSource();
        SCMHead head = new SCMHead("main");

        FakeOriginSCMFacade facade =
                facadeWithSource(source).withRevision(new AbstractGitSCMSource.SCMRevisionImpl(head, BRANCH_SHA));
        facade.withCredentials(someCredentials());

        assertThat(OriginChecksContext.fromRun(run, URL, facade).isValid(listener()), is(true));
        assertThat(buildLog(), is(emptyString()));
    }

    private TaskListener listener() {
        console = new ByteArrayOutputStream();
        return new StreamTaskListener(console, StandardCharsets.UTF_8);
    }

    private String buildLog() {
        return console.toString(StandardCharsets.UTF_8);
    }

    private static OriginSCMSource createSource() {
        OriginSCMSource source = new OriginSCMSource(OWNER, REPOSITORY);
        source.setCredentialsId(CREDENTIALS_ID);
        return source;
    }

    private static FakeOriginSCMFacade facadeWithSource(OriginSCMSource source) {
        return new FakeOriginSCMFacade().withSource(source);
    }

    /** A credentials object only has to exist for these tests; nothing authenticates with it. */
    private static OriginAppCredentials someCredentials() {
        return new OriginAppCredentials(
                CredentialsScope.GLOBAL,
                CREDENTIALS_ID,
                "Test app credentials",
                "test-app-1",
                "inst-42",
                Secret.fromString("not-a-real-key"));
    }

    private static FakeJob fakeJob() {
        return new FakeJob("widgets", "main");
    }

    private static FakeRun fakeRun(FakeJob job) {
        return new FakeRun(job, 3);
    }
}
