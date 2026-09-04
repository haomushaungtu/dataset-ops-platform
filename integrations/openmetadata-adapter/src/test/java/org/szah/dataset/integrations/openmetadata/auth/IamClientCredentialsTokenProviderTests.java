package org.szah.dataset.integrations.openmetadata.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.szah.dataset.integrations.openmetadata.config.AdapterProperties;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IamClientCredentialsTokenProviderTests {
    @Test
    void requestsFreshClientCredentialsTokenWithoutCachingIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AdapterProperties properties = properties("http://iam.test/oauth2/token");
        var provider = new IamClientCredentialsTokenProvider(builder.build(), properties);

        server.expect(twice(), requestTo("http://iam.test/oauth2/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic YWRhcHRlcjphZGFwdGVyLXNlY3JldA=="))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("grant_type=client_credentials&scope=platform.internal"))
                .andRespond(withSuccess("{\"access_token\":\"synthetic-service-token\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.acquire()).isEqualTo("synthetic-service-token");
        assertThat(provider.acquire()).isEqualTo("synthetic-service-token");
        server.verify();
    }

    private static AdapterProperties properties(String tokenUri) {
        return new AdapterProperties(
                new AdapterProperties.Security(URI.create("http://iam.test"), "openmetadata-adapter-api"),
                new AdapterProperties.Iam(URI.create(tokenUri), "adapter", "adapter-secret",
                        "platform.internal"),
                new AdapterProperties.OpenMetadata(URI.create("http://openmetadata.test/api"),
                        Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }
}
