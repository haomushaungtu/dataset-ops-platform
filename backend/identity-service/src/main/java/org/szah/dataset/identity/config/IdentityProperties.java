package org.szah.dataset.identity.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("identity")
public class IdentityProperties {

    private String issuer;
    private boolean allowInsecureLoopback;
    private boolean allowInsecurePrivateNetwork;
    private String resourceAudience = "dataset-platform-api";
    private Duration accessTokenTtl = Duration.ofMinutes(5);
    private Duration refreshTokenTtl = Duration.ofHours(8);
    private final SigningKey signingKey = new SigningKey();
    private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    private final Clients clients = new Clients();

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public boolean isAllowInsecureLoopback() { return allowInsecureLoopback; }
    public void setAllowInsecureLoopback(boolean allowInsecureLoopback) { this.allowInsecureLoopback = allowInsecureLoopback; }
    public boolean isAllowInsecurePrivateNetwork() { return allowInsecurePrivateNetwork; }
    public void setAllowInsecurePrivateNetwork(boolean allowInsecurePrivateNetwork) { this.allowInsecurePrivateNetwork = allowInsecurePrivateNetwork; }
    public String getResourceAudience() { return resourceAudience; }
    public void setResourceAudience(String resourceAudience) { this.resourceAudience = resourceAudience; }
    public Duration getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
    public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
    public SigningKey getSigningKey() { return signingKey; }
    public BootstrapAdmin getBootstrapAdmin() { return bootstrapAdmin; }
    public Clients getClients() { return clients; }

    public static class SigningKey {
        private String location;
        private String storePassword;
        private String alias = "dataset-identity";
        private String keyPassword;
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getStorePassword() { return storePassword; }
        public void setStorePassword(String storePassword) { this.storePassword = storePassword; }
        public String getAlias() { return alias; }
        public void setAlias(String alias) { this.alias = alias; }
        public String getKeyPassword() { return keyPassword; }
        public void setKeyPassword(String keyPassword) { this.keyPassword = keyPassword; }
    }

    public static class BootstrapAdmin {
        private String username;
        private String password;
        private String email;
        private String displayName = "Platform Administrator";
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }

    public static class Clients {
        private final Client openmetadata = new Client();
        private final Client dataverse = new Client();
        private final ServiceClient platformService = new ServiceClient();
        public Client getOpenmetadata() { return openmetadata; }
        public Client getDataverse() { return dataverse; }
        public ServiceClient getPlatformService() { return platformService; }
    }

    public static class Client {
        private String clientId;
        private String clientSecret;
        private List<String> redirectUris = new ArrayList<>();
        private List<String> postLogoutRedirectUris = new ArrayList<>();
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public List<String> getRedirectUris() { return redirectUris; }
        public void setRedirectUris(List<String> redirectUris) { this.redirectUris = redirectUris; }
        public List<String> getPostLogoutRedirectUris() { return postLogoutRedirectUris; }
        public void setPostLogoutRedirectUris(List<String> postLogoutRedirectUris) { this.postLogoutRedirectUris = postLogoutRedirectUris; }
    }

    public static class ServiceClient {
        private String clientId;
        private String clientSecret;
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    }
}
