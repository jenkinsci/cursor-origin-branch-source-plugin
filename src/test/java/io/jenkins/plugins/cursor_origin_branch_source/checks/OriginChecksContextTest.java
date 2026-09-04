package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.hm.hafner.util.FilteredLog;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.cursor_origin_branch_source.CursorOriginAppCredentials;
import io.jenkins.plugins.cursor_origin_branch_source.OriginPullRequestSCMHead;
import io.jenkins.plugins.cursor_origin_branch_source.OriginPullRequestSCMRevision;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.util.Optional;
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

    @Test
    void resolvesRepositoryCoordinatesFromTheSource() {
        Job job = mockJob();
        OriginSCMSource source = createSource();
        OriginSCMFacade facade = mockFacadeWithSource(job, source);

        OriginChecksContext context = OriginChecksContext.fromJob(job, URL, facade);

        assertThat(context.getRepoOwner(), is(OWNER));
        assertThat(context.getRepository(), is(REPOSITORY));
        assertThat(context.getUrl(), is(URL));
        assertThat(context.getJob(), is(job));
        assertThat(context.getRun(), is(Optional.empty()));
    }

    @Test
    void readsTheShaOfABranchBuildFromTheRun() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMSource source = createSource();
        SCMHead head = new SCMHead("main");
        SCMRevision revision = new AbstractGitSCMSource.SCMRevisionImpl(head, BRANCH_SHA);

        OriginSCMFacade facade = mockFacadeWithSource(job, source);
        when(facade.findRevision(source, run)).thenReturn(Optional.of(revision));

        assertThat(OriginChecksContext.fromRun(run, URL, facade).getHeadSha(), is(BRANCH_SHA));
    }

    /**
     * A check reported against the ephemeral merge commit would not surface on the pull request, so
     * the head of the pull request branch is what has to be reported.
     */
    @Test
    void reportsAPullRequestAgainstItsHeadSha() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMSource source = createSource();
        OriginPullRequestSCMHead head = new OriginPullRequestSCMHead("7", "feature-x", "main");
        SCMRevision revision = new OriginPullRequestSCMRevision(head, PR_HEAD_SHA, PR_BASE_SHA);

        OriginSCMFacade facade = mockFacadeWithSource(job, source);
        when(facade.findRevision(source, run)).thenReturn(Optional.of(revision));

        assertThat(OriginChecksContext.fromRun(run, URL, facade).getHeadSha(), is(PR_HEAD_SHA));
    }

    /** With no build yet there is no recorded revision, so the head has to be fetched. */
    @Test
    void fetchesTheShaOfAQueuedBuildFromTheSource() {
        Job job = mockJob();
        OriginSCMSource source = createSource();
        SCMHead head = new SCMHead("main");
        SCMRevision revision = new AbstractGitSCMSource.SCMRevisionImpl(head, BRANCH_SHA);

        OriginSCMFacade facade = mockFacadeWithSource(job, source);
        when(facade.findHead(job)).thenReturn(Optional.of(head));
        when(facade.findRevision(source, head)).thenReturn(Optional.of(revision));

        assertThat(OriginChecksContext.fromJob(job, URL, facade).getHeadSha(), is(BRANCH_SHA));
    }

    @Test
    void unresolvableShaIsReportedRatherThanGuessed() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMFacade facade = mockFacadeWithSource(job, createSource());

        OriginChecksContext context = OriginChecksContext.fromRun(run, URL, facade);

        IllegalStateException e = assertThrows(IllegalStateException.class, context::getHeadSha);
        assertThat(e.getMessage(), is("No SHA found for job: widgets/main"));
    }

    @Test
    void identifiesEachBuildAsItsOwnAttempt() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMFacade facade = mockFacadeWithSource(job, createSource());

        assertThat(OriginChecksContext.fromRun(run, URL, facade).getExternalId(), is("widgets/main#3"));
        assertThat(OriginChecksContext.fromJob(job, URL, facade).getExternalId(), is("widgets/main"));
    }

    /**
     * The suite key is what required-check configuration is keyed on, so it must not encode the branch
     * or the build; without an SCM source owner the job itself is the best available fallback.
     */
    @Test
    void groupsChecksOfAllBranchesIntoOneSuite() {
        Job job = mockJob();
        OriginSCMFacade facade = mockFacadeWithSource(job, createSource());

        OriginChecksContext context = OriginChecksContext.fromJob(job, URL, facade);

        assertThat(context.getSuiteKey(), is("widgets/main"));
        assertThat(context.getSuiteName(), is("widgets » main"));
    }

    @Test
    void isNotValidForAJobWithoutAnOriginSource() {
        Job job = mockJob();
        OriginSCMFacade facade = mock(OriginSCMFacade.class);
        when(facade.findOriginSCMSource(job)).thenReturn(Optional.empty());
        FilteredLog logger = new FilteredLog("errors:");

        assertThat(OriginChecksContext.fromJob(job, URL, facade).isValid(logger), is(false));
        assertThat(logger.getErrorMessages(), hasItem("Job does not use a Cursor Origin SCM source"));
    }

    @Test
    void isNotValidWithoutConfiguredCredentials() {
        Job job = mockJob();
        OriginSCMSource source = new OriginSCMSource(OWNER, REPOSITORY);
        OriginSCMFacade facade = mockFacadeWithSource(job, source);
        FilteredLog logger = new FilteredLog("errors:");

        assertThat(OriginChecksContext.fromJob(job, URL, facade).isValid(logger), is(false));
        assertThat(logger.getErrorMessages(), hasItem("No credentials configured on the Cursor Origin SCM source"));
    }

    @Test
    void isNotValidWhenTheConfiguredCredentialsAreMissing() {
        Job job = mockJob();
        OriginSCMFacade facade = mockFacadeWithSource(job, createSource());
        FilteredLog logger = new FilteredLog("errors:");

        assertThat(OriginChecksContext.fromJob(job, URL, facade).isValid(logger), is(false));
        assertThat(
                logger.getErrorMessages(), hasItem("No Cursor Origin app credentials found with id: 'origin-creds'"));
    }

    @Test
    void isNotValidWithoutAResolvableSha() {
        Job job = mockJob();
        OriginSCMFacade facade = mockFacadeWithSource(job, createSource());
        when(facade.findCredentials(job, CREDENTIALS_ID))
                .thenReturn(Optional.of(mock(CursorOriginAppCredentials.class)));
        FilteredLog logger = new FilteredLog("errors:");

        assertThat(OriginChecksContext.fromJob(job, URL, facade).isValid(logger), is(false));
        assertThat(logger.getErrorMessages(), hasItem("No HEAD SHA found for acme-corp/widgets"));
    }

    @Test
    void isValidForAFullyConfiguredOriginJob() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMSource source = createSource();
        SCMHead head = new SCMHead("main");

        OriginSCMFacade facade = mockFacadeWithSource(job, source);
        when(facade.findRevision(source, run))
                .thenReturn(Optional.of(new AbstractGitSCMSource.SCMRevisionImpl(head, BRANCH_SHA)));
        when(facade.findCredentials(job, CREDENTIALS_ID))
                .thenReturn(Optional.of(mock(CursorOriginAppCredentials.class)));
        FilteredLog logger = new FilteredLog("errors:");

        assertThat(OriginChecksContext.fromRun(run, URL, facade).isValid(logger), is(true));
        assertThat(logger.getErrorMessages(), is(empty()));
    }

    private static OriginSCMSource createSource() {
        OriginSCMSource source = new OriginSCMSource(OWNER, REPOSITORY);
        source.setCredentialsId(CREDENTIALS_ID);
        return source;
    }

    /**
     * Returns a facade that resolves the source and delegates hash extraction to the real
     * implementation, since that is the mapping the context relies on.
     */
    private static OriginSCMFacade mockFacadeWithSource(Job job, OriginSCMSource source) {
        OriginSCMFacade facade = mock(OriginSCMFacade.class);
        when(facade.findOriginSCMSource(job)).thenReturn(Optional.of(source));
        when(facade.findHash(any()))
                .thenAnswer(invocation -> new OriginSCMFacade().findHash(invocation.getArgument(0)));
        return facade;
    }

    private static Job mockJob() {
        Job job = mock(Job.class);
        when(job.getFullName()).thenReturn("widgets/main");
        when(job.getFullDisplayName()).thenReturn("widgets » main");
        return job;
    }

    private static Run mockRun(Job job) {
        Run run = mock(Run.class);
        when(run.getParent()).thenReturn(job);
        when(run.getExternalizableId()).thenReturn("widgets/main#3");
        return run;
    }
}
