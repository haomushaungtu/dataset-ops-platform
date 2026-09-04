package org.szah.dataset.integrations.openmetadata.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientConfigurationTests {
    @Test
    void openMetadataClientSupportsPatchOverTheRealJdkTransport() throws Exception {
        var method = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/resource", exchange -> {
            method.set(exchange.getRequestMethod());
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var properties = new AdapterProperties(
                    new AdapterProperties.Security(URI.create("http://issuer.test"), "audience"),
                    new AdapterProperties.Iam(
                            URI.create("http://issuer.test/token"), "client", "secret", "scope"),
                    new AdapterProperties.OpenMetadata(
                            URI.create("http://127.0.0.1:" + port + "/api"),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2)));
            RestClient client = new HttpClientConfiguration()
                    .openMetadataRestClient(RestClient.builder(), properties);

            client.patch()
                    .uri("/resource")
                    .body("[]")
                    .retrieve()
                    .toBodilessEntity();

            assertThat(method.get()).isEqualTo("PATCH");
        } finally {
            server.stop(0);
        }
    }
}
