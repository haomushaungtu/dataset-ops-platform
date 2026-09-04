package org.szah.dataset.integrations.openmetadata.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class HttpClientConfiguration {
    @Bean
    @Qualifier("iamRestClient")
    RestClient iamRestClient(RestClient.Builder builder, AdapterProperties properties) {
        var factory = requestFactory(properties.openmetadata());
        return builder.requestFactory(factory).build();
    }

    @Bean
    @Qualifier("openMetadataRestClient")
    RestClient openMetadataRestClient(RestClient.Builder builder, AdapterProperties properties) {
        var factory = requestFactory(properties.openmetadata());
        return builder.requestFactory(factory)
                .baseUrl(stripTrailingSlash(properties.openmetadata().baseUrl().toString()))
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(AdapterProperties.OpenMetadata properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
