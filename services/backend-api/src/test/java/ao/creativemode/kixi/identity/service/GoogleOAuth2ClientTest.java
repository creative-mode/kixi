package ao.creativemode.kixi.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.identity.config.GoogleOAuth2Properties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;

class GoogleOAuth2ClientTest {

    private MockWebServer server;
    private GoogleOAuth2Client client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        GoogleOAuth2Properties properties = new GoogleOAuth2Properties();
        properties.setUserInfoUri(server.url("/userinfo").toString());
        client = new GoogleOAuth2Client(properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void mapsStableSubjectAndVerifiedEmailClaims() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"sub":"google-sub-123","email":"student@example.ao","email_verified":true,
                         "name":"Student","picture":"https://example.ao/photo.png"}
                        """));

        StepVerifier.create(client.getUserInfo("access-token"))
                .assertNext(info -> {
                    assertThat(info.sub()).isEqualTo("google-sub-123");
                    assertThat(info.email()).isEqualTo("student@example.ao");
                    assertThat(info.emailVerified()).isTrue();
                    assertThat(info.name()).isEqualTo("Student");
                    assertThat(info.picture()).isEqualTo("https://example.ao/photo.png");
                })
                .verifyComplete();
    }

    @Test
    void supportsLegacyIdAndVerifiedEmailClaims() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"legacy-google-id","email":"student@example.ao","verified_email":true}
                        """));

        StepVerifier.create(client.getUserInfo("access-token"))
                .assertNext(info -> {
                    assertThat(info.sub()).isEqualTo("legacy-google-id");
                    assertThat(info.emailVerified()).isTrue();
                })
                .verifyComplete();
    }
}
