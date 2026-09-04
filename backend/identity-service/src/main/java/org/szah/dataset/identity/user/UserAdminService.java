package org.szah.dataset.identity.user;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.szah.dataset.identity.audit.AuditRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdminService {

    public static final Set<String> ALLOWED_ROLES = Set.of(
            "ADMIN", "OPERATOR", "SUPPLIER", "QUALITY_REVIEWER", "DATA_REVIEWER", "BUYER");
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9._@-]{3,100}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final IdentityUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditRepository audit;

    public UserAdminService(IdentityUserRepository users, PasswordEncoder passwordEncoder,
                            AuditRepository audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional
    public String create(CreateUser command, String actor) {
        validateUsername(command.username());
        validatePassword(command.password());
        validateEmail(command.email());
        Set<String> roles = normalizeRoles(command.roles());
        if (users.findByUsername(command.username()).isPresent()) {
            throw new IllegalArgumentException("username already exists");
        }
        String id = users.createUser(command.username(), passwordEncoder.encode(command.password()),
                command.email(), command.displayName(), command.emailVerified(), roles, actor);
        audit.record(actor, "USER_CREATED", "USER", id, "SUCCESS");
        return id;
    }

    @Transactional
    public void setEnabled(String userId, boolean enabled, String actor) {
        if (!enabled && isSelf(userId, actor)) {
            throw new IllegalArgumentException("an administrator cannot disable their own account");
        }
        if (users.setEnabled(userId, enabled) != 1) {
            throw new IllegalArgumentException("user not found");
        }
        audit.record(actor, enabled ? "USER_ENABLED" : "USER_DISABLED", "USER", userId, "SUCCESS");
    }

    @Transactional
    public void replaceRoles(String userId, Set<String> roles, String actor) {
        Set<String> normalized = normalizeRoles(roles);
        if (!users.existsById(userId)) {
            throw new IllegalArgumentException("user not found");
        }
        if (!normalized.contains("ADMIN") && isSelf(userId, actor)) {
            throw new IllegalArgumentException("an administrator cannot remove their own ADMIN role");
        }
        users.replaceRoles(userId, normalized);
        audit.record(actor, "USER_ROLES_REPLACED", "USER", userId, "SUCCESS");
    }

    @Transactional
    public void resetPassword(String userId, String password, String actor) {
        validatePassword(password);
        if (users.updatePassword(userId, passwordEncoder.encode(password)) != 1) {
            throw new IllegalArgumentException("user not found");
        }
        audit.record(actor, "USER_PASSWORD_RESET", "USER", userId, "SUCCESS");
    }

    @Transactional
    public void grantSupplierRole(String subject, String actor) {
        var profile = users.findProfileBySubject(subject);
        if (profile.isEmpty()) {
            throw new IllegalArgumentException("user not found");
        }
        boolean added = users.addRole(profile.get().id(), "SUPPLIER");
        audit.record(actor, "SUPPLIER_ROLE_GRANTED", "USER", profile.get().id(),
                added ? "SUCCESS" : "UNCHANGED");
    }

    private static Set<String> normalizeRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
        Set<String> normalized = roles.stream().map(role -> role.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!ALLOWED_ROLES.containsAll(normalized)) {
            throw new IllegalArgumentException("unsupported role");
        }
        return normalized;
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 200) {
            throw new IllegalArgumentException("password must contain 12 to 200 characters");
        }
    }

    public static void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username format is invalid");
        }
    }

    public static void validateEmail(String email) {
        if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("email format is invalid");
        }
    }

    private boolean isSelf(String userId, String actor) {
        return userId.equals(actor)
                || users.findProfileBySubject(actor).map(profile -> profile.id().equals(userId)).orElse(false);
    }

    public record CreateUser(String username, String password, String email, String displayName,
                             boolean emailVerified, Set<String> roles) {}
}
