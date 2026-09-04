package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.cursor_origin_branch_source.CursorOriginAppCredentials;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;
import org.junit.jupiter.api.Test;

@SuppressWarnings("rawtypes")
class OriginChecksPublisherFactoryTest {

    private static final String CREDENTIALS_ID = "origin-creds";
    private static final String SHA = "9a41f0c3d2b8e7f6a5c4d3e2f1b0a9c8d7e6f5a4";

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    @Test
    void createsAPublisherForAnOriginBackedBuild() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMSource source = createSource();
        OriginSCMFacade facade = mockFacade(job, source);
        when(facade.findRevision(source, run))
                .thenReturn(Optional.of(new AbstractGitSCMSource.SCMRevisionImpl(new SCMHead("main"), SHA)));
        when(facade.findCredentials(job, CREDENTIALS_ID))
                .thenReturn(Optional.of(mock(CursorOriginAppCredentials.class)));

        Optional<ChecksPublisher> publisher = createFactory(facade).createPublisher(run, listener());

        assertThat(publisher.orElseThrow(), is(instanceOf(OriginChecksPublisher.class)));
    }

    @Test
    void createsAPublisherForAQueuedOriginBackedJob() {
        Job job = mockJob();
        OriginSCMSource source = createSource();
        SCMHead head = new SCMHead("main");
        OriginSCMFacade facade = mockFacade(job, source);
        when(facade.findHead(job)).thenReturn(Optional.of(head));
        when(facade.findRevision(source, head))
                .thenReturn(Optional.of((SCMRevision) new AbstractGitSCMSource.SCMRevisionImpl(head, SHA)));
        when(facade.findCredentials(job, CREDENTIALS_ID))
                .thenReturn(Optional.of(mock(CursorOriginAppCredentials.class)));

        Optional<ChecksPublisher> publisher = createFactory(facade).createPublisher(job, listener());

        assertThat(publisher.orElseThrow(), is(instanceOf(OriginChecksPublisher.class)));
    }

    /** Declining a job that has nothing to do with Cursor Origin is part of the checks API contract. */
    @Test
    void declinesAJobThatIsNotOriginBacked() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMFacade facade = mock(OriginSCMFacade.class);
        when(facade.findOriginSCMSource(job)).thenReturn(Optional.empty());

        assertThat(createFactory(facade).createPublisher(run, listener()), is(Optional.empty()));
    }

    @Test
    void declinesAnOriginJobWithoutCredentials() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMFacade facade = mockFacade(job, new OriginSCMSource("acme-corp", "widgets"));

        assertThat(createFactory(facade).createPublisher(run, listener()), is(Optional.empty()));
    }

    @Test
    void staysQuietAboutJobsItDeclines() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMFacade facade = mockFacade(job, createSource());

        createFactory(facade).createPublisher(run, listener());

        assertThat(consoleLog(), is(emptyString()));
    }

    @Test
    void explainsWhyItDeclinedWhenAskedToBeVerbose() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMSource source = createSource();
        OriginChecksTrait trait = new OriginChecksTrait();
        trait.setVerboseConsoleLog(true);
        source.setTraits(List.of(trait));
        OriginSCMFacade facade = mockFacade(job, source);

        createFactory(facade).createPublisher(run, listener());

        assertThat(consoleLog(), containsString("No Cursor Origin app credentials found with id: 'origin-creds'"));
    }

    @Test
    void reportsCredentialProblemsRatherThanTheAbsenceOfASource() {
        Job job = mockJob();
        Run run = mockRun(job);
        OriginSCMSource source = createSource();
        OriginChecksTrait trait = new OriginChecksTrait();
        trait.setVerboseConsoleLog(true);
        source.setTraits(List.of(trait));
        OriginSCMFacade facade = mockFacade(job, source);

        createFactory(facade).createPublisher(run, listener());

        assertThat(consoleLog(), is(not(containsString("Job does not use a Cursor Origin SCM source"))));
    }

    private OriginChecksPublisherFactory createFactory(OriginSCMFacade facade) {
        DisplayURLProvider urlProvider = mock(DisplayURLProvider.class);
        when(urlProvider.getRunURL(any())).thenReturn("http://localhost:8080/job/widgets/job/main/3/");
        when(urlProvider.getJobURL(any())).thenReturn("http://localhost:8080/job/widgets/job/main/");
        return new OriginChecksPublisherFactory(facade, urlProvider);
    }

    private TaskListener listener() {
        return new StreamTaskListener(console, StandardCharsets.UTF_8);
    }

    private String consoleLog() {
        return console.toString(StandardCharsets.UTF_8);
    }

    private static OriginSCMSource createSource() {
        OriginSCMSource source = new OriginSCMSource("acme-corp", "widgets");
        source.setCredentialsId(CREDENTIALS_ID);
        return source;
    }

    private static OriginSCMFacade mockFacade(Job job, OriginSCMSource source) {
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
