package io.jenkins.plugins.cursor_origin_branch_source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.remoting.Channel;
import java.io.Serializable;
import jenkins.agents.ControllerToAgentCallable;
import jenkins.security.SlaveToMasterCallable;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EncryptedObjectTest {

    @Test
    void roundTrip(JenkinsRule r) throws Exception {
        var ch = r.createOnlineSlave().getChannel();
        assertThat(ch.call(new Out(new EncryptedObject<>(new Thing("xxx", 1)))), is("xxx#1"));
        assertThat(ch.call(new Out(new EncryptedObject<>(new Thing("yyy", 2)))), is("yyy#2"));
    }

    private record Thing(String x, int num) implements Serializable {}

    private record Out(EncryptedObject<Thing> thing) implements ControllerToAgentCallable<String, Exception> {
        @Override
        public String call() throws Exception {
            return Channel.currentOrFail().call(new AndBack(thing));
        }
    }

    private static final class AndBack extends SlaveToMasterCallable<String, Exception> {
        private final EncryptedObject<Thing> thing;

        AndBack(EncryptedObject<Thing> thing) {
            this.thing = thing;
        }

        @Override
        public String call() throws Exception {
            return thing.o().x + "#" + thing.o().num;
        }
    }
}
