package org.szah.dataset.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.szah.dataset.identity.user.IdentityPrincipal;
import org.szah.dataset.identity.user.IdentityUserRepository;
import org.szah.dataset.identity.user.AuthenticationEvents;
import org.szah.dataset.identity.user.UserAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IdentityServiceApplicationTests.TestKeys.class)
class IdentityServiceApplicationTests {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private IdentityUserRepository users;
    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private RegisteredClientRepository clients;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private UserAdminService userAdminService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuthenticationEvents authenticationEvents;

    @Test
    void exposesOidcDiscoveryAndSeedsClients() {
        ResponseEntity<Map> response = http.getForEntity(
                "/.well-known/openid-configuration", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("issuer", "http://127.0.0.1:19000");
        assertThat(response.getBody()).containsKeys("authorization_endpoint", "token_endpoint", "jwks_uri");
        @SuppressWarnings("unchecked")
        var scopes = (java.util.List<String>) response.getBody().get("scopes_supported");
        @SuppressWarnings("unchecked")
        var claims = (java.util.List<String>) response.getBody().get("claims_supported");
        assertThat(scopes)
                .contains("openid", "profile", "email", "groups");
        assertThat(claims)
                .contains("sub", "preferred_username", "email", "roles", "groups", "auth_version");
        var openMetadata = clients.findByClientId("openmetadata-test");
        assertThat(openMetadata).isNotNull();
        assertThat(openMetadata.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(openMetadata.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(openMetadata.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(clients.findByClientId("dataverse-test")).isNotNull();
        assertThat(clients.findByClientId("platform-test")).isNotNull();
    }

    @Test
    void issuesClientCredentialsToken() {
        String targetUsername = "service-role-target-" + UUID.randomUUID();
        String targetId = users.createUser(targetUsername, passwordEncoder.encode("User-Password-2026!"),
                targetUsername + "@example.test", "Service Role Target", true, Set.of("BUYER"), "test");
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("platform-test", "platform-test-secret-2026");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "platform.internal");

        ResponseEntity<Map> response = http.exchange(
                "/oauth2/token", HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("access_token", "expires_in", "token_type");
        assertThat(response.getBody()).doesNotContainKey("refresh_token");

        Jwt jwt = jwtDecoder.decode((String) response.getBody().get("access_token"));
        assertThat(jwt.getAudience()).containsExactlyInAnyOrder("platform-test", "dataset-platform-api");
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(jwt.getTokenValue());
        ResponseEntity<String> userApi = http.exchange(
                "/api/v1/me", HttpMethod.GET, new HttpEntity<>(bearer), String.class);
        assertThat(userApi.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Void> roleGrant = http.exchange(
                "/api/v1/internal/subjects/" + targetId + "/roles/supplier",
                HttpMethod.PUT, new HttpEntity<>(bearer), Void.class);
        assertThat(roleGrant.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(users.roleNames(targetId)).containsExactlyInAnyOrder("BUYER", "SUPPLIER");

        ResponseEntity<Void> replay = http.exchange(
                "/api/v1/internal/subjects/" + targetId + "/roles/supplier",
                HttpMethod.PUT, new HttpEntity<>(bearer), Void.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(users.roleNames(targetId)).containsExactlyInAnyOrder("BUYER", "SUPPLIER");
    }

    @Test
    void protectsManagementApiWithoutBearerToken() {
        ResponseEntity<String> response = http.getForEntity("/api/v1/me", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bootstrapAdminExistsAndDisabledUserNoLongerAuthenticates() {
        IdentityPrincipal principal = users.findByUsername("test-admin").orElseThrow();
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.getAuthorities()).extracting(Object::toString).contains("ROLE_ADMIN");
        assertThat(userDetailsService.loadUserByUsername("test-admin"))
                .isInstanceOf(org.springframework.security.core.userdetails.User.class);

        String username = "disabled-" + UUID.randomUUID();
        String userId = users.createUser(username, passwordEncoder.encode("User-Password-2026!"),
                username + "@example.test", "Disabled Test User", true, Set.of("BUYER"), "test");
        users.setEnabled(userId, false);
        IdentityPrincipal disabled = users.findByUsername(username).orElseThrow();
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(userDetailsService.loadUserByUsername(username).isEnabled()).isFalse();
    }

    @Test
    void rejectsUnsafeAdministrativeChanges() {
        IdentityPrincipal admin = users.findByUsername("test-admin").orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        userAdminService.setEnabled(admin.id(), false, "test-admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot disable their own account");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        userAdminService.replaceRoles(admin.id(), Set.of("OPERATOR"), "test-admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot remove their own ADMIN role");
    }

    @Test
    void locksAfterFiveFailuresAndSuccessfulAuthenticationResetsTheCounter() {
        String username = "lock-" + UUID.randomUUID();
        users.createUser(username, passwordEncoder.encode("User-Password-2026!"),
                username + "@example.test", "Lock Test User", true, Set.of("BUYER"), "test");
        for (int attempt = 0; attempt < 5; attempt++) {
            authenticationEvents.onFailure(new AuthenticationFailureBadCredentialsEvent(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, "wrong"),
                    new BadCredentialsException("invalid credentials")));
        }
        assertThat(userDetailsService.loadUserByUsername(username).isAccountNonLocked()).isFalse();

        var authenticated = UsernamePasswordAuthenticationToken.authenticated(
                username, null, java.util.List.of());
        authenticationEvents.onSuccess(new AuthenticationSuccessEvent(authenticated));
        assertThat(userDetailsService.loadUserByUsername(username).isAccountNonLocked()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestKeys {
        @Bean
        JWKSource<SecurityContext> testJwkSource() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("test-key")
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(key));
        }
    }
}
