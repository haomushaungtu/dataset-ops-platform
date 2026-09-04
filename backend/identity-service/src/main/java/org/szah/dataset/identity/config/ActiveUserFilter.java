package org.szah.dataset.identity.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.szah.dataset.identity.user.IdentityUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ActiveUserFilter extends OncePerRequestFilter {

    private final IdentityUserRepository users;

    public ActiveUserFilter(IdentityUserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            boolean serviceAccount = jwtAuthentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("SCOPE_platform.internal"))
                    && jwtAuthentication.getToken().getClaimAsString("preferred_username") == null;
            if (serviceAccount) {
                filterChain.doFilter(request, response);
                return;
            }
            String tokenVersion = jwtAuthentication.getToken().getClaimAsString("auth_version");
            var profile = users.findProfileBySubject(jwtAuthentication.getToken().getSubject());
            boolean active = profile.isPresent() && profile.get().enabled()
                    && tokenVersion != null
                    && tokenVersion.equals(Long.toString(profile.get().authVersion()));
            if (!active) {
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "token is no longer active");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
