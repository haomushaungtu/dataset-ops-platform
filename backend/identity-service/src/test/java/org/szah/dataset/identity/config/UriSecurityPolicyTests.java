package org.szah.dataset.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class UriSecurityPolicyTests {

    @Test
    void permitsRfc1918HttpOnlyWhenExplicitlyEnabled() {
        IdentityProperties properties = new IdentityProperties();
        URI privateIssuer = URI.create("http://10.100.165.139:19000");

        assertThat(UriSecurityPolicy.isTransportAllowed(privateIssuer, properties)).isFalse();
        properties.setAllowInsecurePrivateNetwork(true);
        assertThat(UriSecurityPolicy.isTransportAllowed(privateIssuer, properties)).isTrue();
        assertThat(UriSecurityPolicy.isTransportAllowed(
                URI.create("http://172.31.1.9:19000"), properties)).isTrue();
        assertThat(UriSecurityPolicy.isTransportAllowed(
                URI.create("http://192.168.20.9:19000"), properties)).isTrue();
    }

    @Test
    void rejectsPublicMalformedAndNonHttpInsecureUris() {
        IdentityProperties properties = new IdentityProperties();
        properties.setAllowInsecurePrivateNetwork(true);

        assertThat(UriSecurityPolicy.isTransportAllowed(
                URI.create("http://8.8.8.8:19000"), properties)).isFalse();
        assertThat(UriSecurityPolicy.isTransportAllowed(
                URI.create("http://10.300.1.1:19000"), properties)).isFalse();
        assertThat(UriSecurityPolicy.isTransportAllowed(
                URI.create("ftp://10.100.165.139/resource"), properties)).isFalse();
        assertThat(UriSecurityPolicy.isTransportAllowed(
                URI.create("https://identity.example.internal"), properties)).isTrue();
    }
}
