package io.jenkins.plugins.cursor_origin_branch_source.checks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OriginChecksEventSubscriberTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── user cause ───────────────────────────────────────────────────────────

    @Test
    void userCauseWithAllFieldsPresent() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {
                  "checkRun": {
                    "rerequestedBy": {
                      "user": {
                        "id": "user_123",
                        "email": "jane@acme.dev",
                        "displayName": "Jane Doe"
                      }
                    }
                  }
                }
                """);

        OriginCheckRerunCause cause = OriginChecksEventSubscriber.createCauseFromPayload(payload);

        assertThat(cause, instanceOf(OriginCheckRerunCause.OriginCheckRerunUserCause.class));
        OriginCheckRerunCause.OriginCheckRerunUserCause userCause =
                (OriginCheckRerunCause.OriginCheckRerunUserCause) cause;
        assertThat(userCause.getId(), is("user_123"));
        assertThat(userCause.getEmail(), is("jane@acme.dev"));
        assertThat(userCause.getDisplayName(), is("Jane Doe"));
    }

    @Test
    void userCauseWithoutOptionalDisplayName() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {
                  "checkRun": {
                    "rerequestedBy": {
                      "user": {
                        "id": "user_123",
                        "email": "jane@acme.dev"
                      }
                    }
                  }
                }
                """);

        OriginCheckRerunCause cause = OriginChecksEventSubscriber.createCauseFromPayload(payload);

        assertThat(cause, instanceOf(OriginCheckRerunCause.OriginCheckRerunUserCause.class));
        OriginCheckRerunCause.OriginCheckRerunUserCause userCause =
                (OriginCheckRerunCause.OriginCheckRerunUserCause) cause;
        assertThat(userCause.getId(), is("user_123"));
        assertThat(userCause.getEmail(), is("jane@acme.dev"));
        assertThat(userCause.getDisplayName(), is(nullValue()));
    }

    // ── app cause ────────────────────────────────────────────────────────────

    @Test
    void appCauseWithDisplayName() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {
                  "checkRun": {
                    "rerequestedBy": {
                      "app": {
                        "id": "app_456",
                        "displayName": "Acme CI"
                      }
                    }
                  },
                  "repository": {
                    "owner": {
                      "slug": "acme-corp"
                    }
                  }
                }
                """);

        OriginCheckRerunCause cause = OriginChecksEventSubscriber.createCauseFromPayload(payload);

        assertThat(cause, instanceOf(OriginCheckRerunCause.OriginCheckRerunAppCause.class));
        OriginCheckRerunCause.OriginCheckRerunAppCause appCause =
                (OriginCheckRerunCause.OriginCheckRerunAppCause) cause;
        assertThat(appCause.getAppId(), is("app_456"));
        assertThat(appCause.getDisplayName(), is("Acme CI"));
        assertThat(appCause.getNamesapce(), is("acme-corp"));
    }

    @Test
    void appCauseWithoutOptionalDisplayName() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {
                  "checkRun": {
                    "rerequestedBy": {
                      "app": {
                        "id": "app_456"
                      }
                    }
                  },
                  "repository": {
                    "owner": {
                      "slug": "acme-corp"
                    }
                  }
                }
                """);

        OriginCheckRerunCause cause = OriginChecksEventSubscriber.createCauseFromPayload(payload);

        assertThat(cause, instanceOf(OriginCheckRerunCause.OriginCheckRerunAppCause.class));
        OriginCheckRerunCause.OriginCheckRerunAppCause appCause =
                (OriginCheckRerunCause.OriginCheckRerunAppCause) cause;
        assertThat(appCause.getAppId(), is("app_456"));
        assertThat(appCause.getDisplayName(), is(nullValue()));
        assertThat(appCause.getNamesapce(), is("acme-corp"));
    }

    // ── service account cause ────────────────────────────────────────────────

    @Test
    void serviceAccountCause() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {
                  "checkRun": {
                    "rerequestedBy": {
                      "serviceAccount": {
                        "id": "sa_789"
                      }
                    }
                  }
                }
                """);

        OriginCheckRerunCause cause = OriginChecksEventSubscriber.createCauseFromPayload(payload);

        assertThat(cause, instanceOf(OriginCheckRerunCause.OriginCheckRerunServiceAccountCause.class));
        assertThat(
                ((OriginCheckRerunCause.OriginCheckRerunServiceAccountCause) cause).getId(), is("sa_789"));
    }

    // ── fallback cause ───────────────────────────────────────────────────────

    @Test
    void fallbackCauseWhenRerequestedByIsAbsent() throws Exception {
        JsonNode payload = MAPPER.readTree("""
                {
                  "checkRun": {}
                }
                """);

        OriginCheckRerunCause cause = OriginChecksEventSubscriber.createCauseFromPayload(payload);

        assertThat(cause.getClass(), is(OriginCheckRerunCause.class));
    }
}
