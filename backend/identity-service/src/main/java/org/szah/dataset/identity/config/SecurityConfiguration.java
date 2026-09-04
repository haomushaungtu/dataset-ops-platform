package org.szah.dataset.identity.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.szah.dataset.identity.user.IdentityUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()))
                .with(authorizationServer, server -> server.oidc(oidc -> oidc
                        .providerConfigurationEndpoint(endpoint -> endpoint
                                .providerConfigurationCustomizer(metadata -> metadata
                                        .scope(OidcScopes.PROFILE)
                                        .scope(OidcScopes.EMAIL)
                                        .scope("groups")
                                        .claim("claims_supported", List.of(
                                                "sub", "preferred_username", "email", "name",
                                                "roles", "groups", "auth_version"))))))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ActiveUserFilter activeUserFilter) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/internal/**").hasAuthority("SCOPE_platform.internal")
                        .requestMatchers("/api/v1/me").access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current instanceof JwtAuthenticationToken jwt
                                    && jwt.getToken().getClaimAsString("preferred_username") != null);
                        })
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterAfter(activeUserFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/error", "/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(logout -> logout.clearAuthentication(true).invalidateHttpSession(true));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(IdentityProperties properties) {
        if (!StringUtils.hasText(properties.getIssuer())) {
            throw new IllegalStateException("identity.issuer is required");
        }
        validateIssuer(properties);
        return AuthorizationServerSettings.builder().issuer(properties.getIssuer()).build();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> identityClaims(IdentityUserRepository users,
                                                              IdentityProperties properties) {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                if (!StringUtils.hasText(properties.getResourceAudience())) {
                    throw new IllegalStateException("identity.resource-audience is required");
                }
                var audiences = new java.util.LinkedHashSet<String>();
                audiences.add(context.getRegisteredClient().getClientId());
                audiences.add(properties.getResourceAudience());
                context.getClaims().audience(new ArrayList<>(audiences));
            }
            String username = context.getPrincipal().getName();
            users.findProfile(username).ifPresent(profile -> {
                if (!profile.enabled()) {
                    throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                            new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED), "user is disabled");
                }
                List<String> roles = new ArrayList<>(profile.roles());
                roles.sort(String::compareTo);
                context.getClaims().subject(profile.id());
                context.getClaims().claim("preferred_username", profile.username());
                context.getClaims().claim("email", profile.email());
                context.getClaims().claim("name", profile.displayName());
                context.getClaims().claim("roles", new ArrayList<>(roles));
                context.getClaims().claim("groups", new ArrayList<>(roles));
                context.getClaims().claim("auth_version", Long.toString(profile.authVersion()));
            });
        };
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            Collection<GrantedAuthority> roleAuthorities = roles.convert(jwt);
            Collection<GrantedAuthority> scopeAuthorities = scopes.convert(jwt);
            if (roleAuthorities != null) {
                authorities.addAll(roleAuthorities);
            }
            if (scopeAuthorities != null) {
                authorities.addAll(scopeAuthorities);
            }
            return authorities;
        });
        return converter;
    }

    @Bean
    @Profile("!test")
    JWKSource<SecurityContext> productionJwkSource(IdentityProperties properties) throws Exception {
        IdentityProperties.SigningKey config = properties.getSigningKey();
        require(config.getLocation(), "identity.signing-key.location");
        require(config.getStorePassword(), "identity.signing-key.store-password");
        require(config.getAlias(), "identity.signing-key.alias");
        require(config.getKeyPassword(), "identity.signing-key.key-password");
        requireStrongPassword(config.getStorePassword(), "identity.signing-key.store-password");
        requireStrongPassword(config.getKeyPassword(), "identity.signing-key.key-password");

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = new FileSystemResource(config.getLocation()).getInputStream()) {
            keyStore.load(input, config.getStorePassword().toCharArray());
        }
        Key key = keyStore.getKey(config.getAlias(), config.getKeyPassword().toCharArray());
        Certificate certificate = keyStore.getCertificate(config.getAlias());
        if (!(key instanceof RSAPrivateKey privateKey)
                || certificate == null
                || !(certificate.getPublicKey() instanceof RSAPublicKey publicKey)) {
            throw new IllegalStateException("configured signing entry must contain an RSA private key and certificate");
        }
        RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey)
                .keyID(config.getAlias()).build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private static void require(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " is required");
        }
    }

    private static void requireStrongPassword(String value, String property) {
        if (value.length() < 16) {
            throw new IllegalStateException(property + " must contain at least 16 characters");
        }
    }

    private static void validateIssuer(IdentityProperties properties) {
        java.net.URI issuer;
        try {
            issuer = java.net.URI.create(properties.getIssuer());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("identity.issuer is invalid", exception);
        }
        if (!issuer.isAbsolute() || issuer.getUserInfo() != null || issuer.getQuery() != null
                || issuer.getFragment() != null
                || !UriSecurityPolicy.isTransportAllowed(issuer, properties)) {
            throw new IllegalStateException("identity.issuer must be an absolute HTTPS URI; explicitly enabled loopback or RFC1918 IPv4 development HTTP is the only exception");
        }
    }
}
