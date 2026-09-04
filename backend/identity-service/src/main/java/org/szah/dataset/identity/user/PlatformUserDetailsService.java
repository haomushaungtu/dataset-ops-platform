package org.szah.dataset.identity.user;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PlatformUserDetailsService implements UserDetailsService {

    private final IdentityUserRepository users;

    public PlatformUserDetailsService(IdentityUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        IdentityPrincipal principal = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
        return org.springframework.security.core.userdetails.User.withUsername(principal.username())
                .password(principal.password())
                .authorities(principal.getAuthorities())
                .disabled(!principal.enabled())
                .accountLocked(!principal.accountNonLocked())
                .build();
    }
}
