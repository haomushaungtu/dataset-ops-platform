package org.szah.dataset.integrations.openmetadata.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.szah.dataset.integrations.openmetadata.config.AdapterProperties;
import org.szah.dataset.integrations.openmetadata.sync.MetadataSyncException;

@Component
public final class IamClientCredentialsTokenProvider implements ServiceTokenProvider {
    private final RestClient client;
    private final AdapterProperties.Iam properties;

    public IamClientCredentialsTokenProvider(
            @Qualifier("iamRestClient") RestClient client,
            AdapterProperties properties) {
        this.client = client;
        this.properties = properties.iam();
    }

    @Override
    public String acquire() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("scope", properties.scope());
        try {
            TokenResponse response = client.post()
                    .uri(properties.tokenUri())
                    .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new MetadataSyncException("IAM_TOKEN_INVALID",
                        "IAM token response did not contain an access token");
            }
            return response.accessToken();
        } catch (MetadataSyncException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new MetadataSyncException("IAM_TOKEN_REQUEST_FAILED",
                    "IAM client_credentials request failed", exception);
        }
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
