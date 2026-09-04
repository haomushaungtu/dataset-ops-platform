package org.szah.dataset.identity.user;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record IdentityPrincipal(
        String id,
        String username,
        String password,
        String email,
        String displayName,
        boolean enabled,
        boolean accountNonLocked,
        long authVersion,
        List<GrantedAuthority> authorities) implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }
}
