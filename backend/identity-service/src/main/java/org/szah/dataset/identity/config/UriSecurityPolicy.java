package org.szah.dataset.identity.config;

import java.net.URI;

final class UriSecurityPolicy {

    private UriSecurityPolicy() {}

    static boolean isTransportAllowed(URI uri, IdentityProperties properties) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        return properties.isAllowInsecureLoopback() && isLoopback(host)
                || properties.isAllowInsecurePrivateNetwork() && isRfc1918Ipv4(host);
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }

    private static boolean isRfc1918Ipv4(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            return false;
        }
        String[] parts = host.split("\\.");
        int[] octets = new int[4];
        for (int index = 0; index < parts.length; index++) {
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) {
                return false;
            }
        }
        return octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
    }
}
