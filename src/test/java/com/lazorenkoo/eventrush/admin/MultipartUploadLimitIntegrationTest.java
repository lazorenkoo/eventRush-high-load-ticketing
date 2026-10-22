package com.lazorenkoo.eventrush.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///multipart_limits_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "eventrush.admin.api-key=multipart-test-key-12345678901234"
})
class MultipartUploadLimitIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    private static final String BOUNDARY = "EventRushTestBoundary";

    private HttpRequest.BodyPublisher part(String name, int size) {
        String header = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"private-client-names.csv\"\r\nContent-Type: text/csv\r\n\r\n";
        return HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(header),
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[size])),
                HttpRequest.BodyPublishers.ofString("\r\n"));
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/admin/concerts/" + UUID.randomUUID() + "/load-runs"))
                .timeout(Duration.ofSeconds(15)).header("Origin", "http://localhost:8082")
                .header("X-Admin-Key", "multipart-test-key-12345678901234")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.concat(body,
                        HttpRequest.BodyPublishers.ofString("--" + BOUNDARY + "--\r\n"))).build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private void rejected(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("private-client-names", "Exception", "org.apache");
        assertThat(jdbc.queryForObject("select count(*) from load_test_runs", Integer.class)).isZero();
    }

    @Test
    void servletRejectsFileAboveSixteenMiB() throws Exception {
        rejected(send(part("file", 16 * 1024 * 1024 + 1)));
    }

    @Test
    void servletRejectsCombinedRequestAboveSeventeenMiB() throws Exception {
        rejected(send(HttpRequest.BodyPublishers.concat(part("file", 9 * 1024 * 1024),
                part("extra", 9 * 1024 * 1024))));
    }

    @Test
    void smallMultipartReachesCsvValidation() throws Exception {
        assertThat(send(part("file", 1)).statusCode()).isEqualTo(400);
    }
}