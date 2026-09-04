package org.szah.dataset.identity.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityUserRepository {

    private final JdbcTemplate jdbc;

    public IdentityUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countUsers() {
        return jdbc.queryForObject("select count(*) from iam_user", Long.class);
    }

    public Optional<IdentityPrincipal> findByUsername(String username) {
        List<UserRow> rows = jdbc.query("""
                select id, username, password_hash, email, display_name, enabled,
                       failed_login_count, locked_until, auth_version
                from iam_user where username_normalized = ?
                """, USER_ROW_MAPPER, normalize(username));
        return rows.stream().findFirst().map(this::toPrincipal);
    }

    public Optional<UserProfile> findProfile(String username) {
        return findByUsername(username).map(user -> new UserProfile(
                user.id(), user.username(), user.email(), user.displayName(), user.enabled(),
                user.authVersion(), roleNames(user.id())));
    }

    public Optional<UserProfile> findProfileById(String id) {
        List<UserRow> rows = jdbc.query("""
                select id, username, password_hash, email, display_name, enabled,
                       failed_login_count, locked_until, auth_version
                from iam_user where id = ?
                """, USER_ROW_MAPPER, id);
        return rows.stream().findFirst().map(user -> new UserProfile(
                user.id(), user.username(), user.email(), user.displayName(), user.enabled(),
                user.authVersion(), roleNames(user.id())));
    }

    public Optional<UserProfile> findProfileBySubject(String subject) {
        return findProfileById(subject).or(() -> findProfile(subject));
    }

    public String createUser(String username, String passwordHash, String email, String displayName,
                             boolean emailVerified, Set<String> roles, String actor) {
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                insert into iam_user
                  (id, username, username_normalized, password_hash, email, email_normalized,
                   display_name, email_verified, enabled,
                   auth_version, created_at, updated_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, true, 1, ?, ?, ?)
                """, id, username, normalize(username), passwordHash, email, normalize(email),
                displayName, emailVerified, now, now, actor);
        replaceRoles(id, roles);
        return id;
    }

    public void replaceRoles(String userId, Set<String> roles) {
        jdbc.update("delete from iam_user_role where user_id = ?", userId);
        roles.forEach(role -> jdbc.update(
                "insert into iam_user_role (user_id, role_code) values (?, ?)", userId, role));
        jdbc.update("update iam_user set auth_version = auth_version + 1, updated_at = ? where id = ?",
                OffsetDateTime.now(), userId);
    }

    public boolean addRole(String userId, String role) {
        int added = jdbc.update("""
                insert into iam_user_role (user_id, role_code)
                select ?, ? where not exists (
                    select 1 from iam_user_role where user_id = ? and role_code = ?
                )
                """, userId, role, userId, role);
        if (added == 1) {
            jdbc.update("update iam_user set auth_version = auth_version + 1, updated_at = ? where id = ?",
                    OffsetDateTime.now(), userId);
        }
        return added == 1;
    }

    public int setEnabled(String userId, boolean enabled) {
        return jdbc.update("""
                update iam_user set enabled = ?, auth_version = auth_version + 1, updated_at = ?
                where id = ?
                """, enabled, OffsetDateTime.now(), userId);
    }

    public int updatePassword(String userId, String passwordHash) {
        return jdbc.update("""
                update iam_user set password_hash = ?, auth_version = auth_version + 1, updated_at = ?
                where id = ?
                """, passwordHash, OffsetDateTime.now(), userId);
    }

    public boolean existsById(String userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select count(*) > 0 from iam_user where id = ?", Boolean.class, userId));
    }

    public void recordLoginFailure(String username, int maximumFailures, java.time.Duration lockDuration) {
        jdbc.update("""
                update iam_user
                set failed_login_count = failed_login_count + 1,
                    locked_until = case when failed_login_count + 1 >= ? then ? else locked_until end,
                    updated_at = ?
                where username_normalized = ?
                """, maximumFailures, OffsetDateTime.now().plus(lockDuration), OffsetDateTime.now(),
                normalize(username));
    }

    public void recordLoginSuccess(String username) {
        jdbc.update("""
                update iam_user set failed_login_count = 0, locked_until = null, updated_at = ?
                where username_normalized = ?
                """, OffsetDateTime.now(), normalize(username));
    }

    public Set<String> roleNames(String userId) {
        return Set.copyOf(jdbc.queryForList(
                "select role_code from iam_user_role where user_id = ? order by role_code",
                String.class, userId));
    }

    private IdentityPrincipal toPrincipal(UserRow row) {
        List<SimpleGrantedAuthority> authorities = roleNames(row.id()).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        boolean accountNonLocked = row.lockedUntil() == null
                || !row.lockedUntil().isAfter(OffsetDateTime.now());
        return new IdentityPrincipal(row.id(), row.username(), row.passwordHash(), row.email(),
                row.displayName(), row.enabled(), accountNonLocked, row.authVersion(),
                List.copyOf(authorities));
    }

    private static final RowMapper<UserRow> USER_ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static UserRow mapRow(ResultSet rs) throws SQLException {
        return new UserRow(rs.getString("id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("email"),
                rs.getString("display_name"), rs.getBoolean("enabled"),
                rs.getInt("failed_login_count"),
                rs.getObject("locked_until", OffsetDateTime.class),
                rs.getLong("auth_version"));
    }

    private record UserRow(String id, String username, String passwordHash, String email,
                           String displayName, boolean enabled, int failedLoginCount,
                           OffsetDateTime lockedUntil, long authVersion) {}

    public record UserProfile(String id, String username, String email, String displayName,
                              boolean enabled, long authVersion, Set<String> roles) {}

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
