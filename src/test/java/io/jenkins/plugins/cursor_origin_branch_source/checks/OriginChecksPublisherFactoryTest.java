package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.cursor_origin_branch_source.OriginAppCredentials;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMHead;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;
import org.junit.jupiter.api.Test;

@SuppressWarnings("rawtypes")
class OriginChecksPublisherFactoryTest {

    private static final String CREDENTIALS_ID = "origin-creds";
    private static final String SHA = "9a41f0c3d2b8e7f6a5c4d3e2f1b0a9c8d7e6f5a4";

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    @Test
    void createsAPublisherForAnOriginBackedBuild() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        OriginSCMSource source = createSource();
        FakeOriginSCMFacade facade = facadeWithSource(source)
                .withRevision(new AbstractGitSCMSource.SCMRevisionImpl(new SCMHead("main"), SHA))
                .withCredentials(someCredentials());

        Optional<ChecksPublisher> publisher = createFactory(facade).createPublisher(run, listener());

        assertThat(publisher.orElseThrow(), is(instanceOf(OriginChecksPublisher.class)));
    }

    /** Declining a job that has nothing to do with Cursor Origin is part of the checks API contract. */
    @Test
    void declinesAJobThatIsNotOriginBacked() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = new FakeOriginSCMFacade();

        assertThat(createFactory(facade).createPublisher(run, listener()), is(Optional.empty()));
    }

    @Test
    void declinesAnOriginJobWithoutCredentials() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = facadeWithSource(new OriginSCMSource("acme-corp", "widgets"));

        assertThat(createFactory(facade).createPublisher(run, listener()), is(Optional.empty()));
    }

    /** Declining a job that is not Origin backed is normal, so it is not worth a word in its log. */
    @Test
    void staysQuietAboutJobsThatAreNotOriginBacked() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = new FakeOriginSCMFacade();

        createFactory(facade).createPublisher(run, listener());

        assertThat(consoleLog(), is(emptyString()));
    }

    @Test
    void explainsWhyItDeclinedAnOriginBackedJob() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = facadeWithSource(createSource());

        createFactory(facade).createPublisher(run, listener());

        assertThat(consoleLog(), containsString("No Cursor Origin app credentials found with id: 'origin-creds'"));
    }

    @Test
    void reportsCredentialProblemsRatherThanTheAbsenceOfASource() {
        FakeJob job = fakeJob();
        FakeRun run = fakeRun(job);
        FakeOriginSCMFacade facade = facadeWithSource(createSource());

        createFactory(facade).createPublisher(run, listener());

        assertThat(consoleLog(), is(not(containsString("Job does not use a Cursor Origin SCM source"))));
    }

    private OriginChecksPublisherFactory createFactory(OriginSCMFacade facade) {
        return new OriginChecksPublisherFactory(facade, new FixedDisplayURLProvider());
    }

    /** Supplies the one URL the factory asks for; anything else would be an unnoticed new dependency. */
    private static final class FixedDisplayURLProvider extends DisplayURLProvider {

        @Override
        public String getRunURL(Run<?, ?> run) {
            return "http://localhost:8080/job/widgets/job/main/3/";
        }

        @Override
        public String getJobURL(Job<?, ?> job) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getChangesURL(Run<?, ?> run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getTestsURL(Run<?, ?> run) {
            throw new UnsupportedOperationException();
        }
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
