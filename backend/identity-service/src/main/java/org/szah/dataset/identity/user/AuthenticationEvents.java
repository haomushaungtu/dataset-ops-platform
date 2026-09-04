package org.szah.dataset.identity.user;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.szah.dataset.identity.audit.AuditRepository;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationEvents {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationEvents.class);
    private static final int MAXIMUM_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private final IdentityUserRepository users;
    private final AuditRepository audit;

    public AuthenticationEvents(IdentityUserRepository users, AuditRepository audit) {
        this.users = users;
        this.audit = audit;
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        String username = event.getAuthentication().getName();
        users.recordLoginFailure(username, MAXIMUM_FAILURES, LOCK_DURATION);
        audit.record(username, "LOGIN_FAILED", "USER", username, "FAILURE");
        log.info("Authentication failed for a known or unknown principal");
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        users.findProfile(username).ifPresent(profile -> {
            users.recordLoginSuccess(profile.username());
            audit.record(profile.username(), "LOGIN_SUCCEEDED", "USER", profile.id(), "SUCCESS");
        });
    }
}
