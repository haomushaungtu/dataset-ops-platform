package org.szah.dataset.identity.config;

import java.util.Set;
import java.util.UUID;
import java.net.URI;
import org.szah.dataset.identity.user.IdentityUserRepository;
import org.szah.dataset.identity.user.UserAdminService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class IdentityBootstrap implements ApplicationRunner {

    private final IdentityProperties properties;
    private final IdentityUserRepository users;
    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;

    public IdentityBootstrap(IdentityProperties properties, IdentityUserRepository users,
                             RegisteredClientRepository clients, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.users = users;
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapAdmin();
        saveInteractiveClient("openmetadata", properties.getClients().getOpenmetadata());
        saveInteractiveClient("dataverse", properties.getClients().getDataverse());
        saveServiceClient("platform-service", properties.getClients().getPlatformService());
        saveServiceClient("openmetadata-adapter", properties.getClients().getOpenmetadataAdapter());
    }

    private void bootstrapAdmin() {
        if (users.countUsers() != 0) {
            return;
        }
        IdentityProperties.BootstrapAdmin admin = properties.getBootstrapAdmin();
        require(admin.getUsername(), "identity.bootstrap-admin.username");
        require(admin.getPassword(), "identity.bootstrap-admin.password");
        require(admin.getEmail(), "identity.bootstrap-admin.email");
        UserAdminService.validateUsername(admin.getUsername());
        UserAdminService.validatePassword(admin.getPassword());
        UserAdminService.validateEmail(admin.getEmail());
        users.createUser(admin.getUsername(), passwordEncoder.encode(admin.getPassword()),
                admin.getEmail(), admin.getDisplayName(), true, Set.of("ADMIN"), "bootstrap");
    }

    private void saveInteractiveClient(String name, IdentityProperties.Client config) {
        require(config.getClientId(), "identity.clients." + name + ".client-id");
        require(config.getClientSecret(), "identity.clients." + name + ".client-secret");
        requireStrongSecret(config.getClientSecret(), "identity.clients." + name + ".client-secret");
        if (config.getRedirectUris().isEmpty()) {
            throw new IllegalStateException("identity.clients." + name + ".redirect-uris is required");
        }
        RegisteredClient existing = clients.findByClientId(config.getClientId());
        RegisteredClient.Builder builder = RegisteredClient.withId(existing == null ? UUID.randomUUID().toString() : existing.getId())
                .clientId(config.getClientId())
                .clientName(name)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope(OidcScopes.EMAIL)
                .scope("groups")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(tokenSettings());
        config.getRedirectUris().stream().filter(StringUtils::hasText)
                .forEach(uri -> builder.redirectUri(validateUri(uri, "redirect URI")));
        config.getPostLogoutRedirectUris().stream().filter(StringUtils::hasText)
                .forEach(uri -> builder.postLogoutRedirectUri(validateUri(uri, "post-logout redirect URI")));
        builder.clientSecret(encodedSecret(existing, config.getClientSecret()));
        clients.save(builder.build());
    }

    private void saveServiceClient(String name, IdentityProperties.ServiceClient config) {
        require(config.getClientId(), "identity.clients." + name + ".client-id");
        require(config.getClientSecret(), "identity.clients." + name + ".client-secret");
        requireStrongSecret(config.getClientSecret(), "identity.clients." + name + ".client-secret");
        RegisteredClient existing = clients.findByClientId(config.getClientId());
        clients.save(RegisteredClient.withId(existing == null ? UUID.randomUUID().toString() : existing.getId())
                .clientId(config.getClientId())
                .clientName(name)
                .clientSecret(encodedSecret(existing, config.getClientSecret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("platform.internal")
                .tokenSettings(tokenSettings())
                .build());
    }

    private TokenSettings tokenSettings() {
        return TokenSettings.builder()
                .accessTokenTimeToLive(properties.getAccessTokenTtl())
                .refreshTokenTimeToLive(properties.getRefreshTokenTtl())
                .reuseRefreshTokens(false)
                .build();
    }

    private String encodedSecret(RegisteredClient existing, String rawSecret) {
        if (existing != null && StringUtils.hasText(existing.getClientSecret())
                && passwordEncoder.matches(rawSecret, existing.getClientSecret())) {
            return existing.getClientSecret();
        }
        return passwordEncoder.encode(rawSecret);
    }

    private static void require(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " is required");
        }
    }

    private static void requireStrongSecret(String value, String property) {
        if (value.length() < 24) {
            throw new IllegalStateException(property + " must contain at least 24 characters");
        }
    }

    private String validateUri(String value, String label) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(label + " is invalid", exception);
        }
        if (!uri.isAbsolute() || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException(label + " must be an absolute URI without user info or fragment");
        }
        if (!UriSecurityPolicy.isTransportAllowed(uri, properties)) {
            throw new IllegalStateException(label
                    + " must use HTTPS; explicitly enabled loopback or RFC1918 IPv4 development HTTP is the only exception");
        }
        return uri.toString();
    }
}
