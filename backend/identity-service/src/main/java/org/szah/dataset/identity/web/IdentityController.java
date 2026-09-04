package org.szah.dataset.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.szah.dataset.identity.user.UserAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {

    private final UserAdminService users;

    public IdentityController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return Map.of(
                "sub", jwt.getSubject(),
                "username", jwt.getClaimAsString("preferred_username"),
                "email", jwt.getClaimAsString("email"),
                "name", jwt.getClaimAsString("name"),
                "roles", defaultList(jwt.getClaimAsStringList("roles")));
    }

    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@Valid @RequestBody CreateUserRequest request,
                                      Authentication authentication) {
        String id = users.create(new UserAdminService.CreateUser(request.username(), request.password(),
                request.email(), request.displayName(), request.emailVerified(), request.roles()),
                authentication.getName());
        return Map.of("id", id);
    }

    @PatchMapping("/admin/users/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setStatus(@PathVariable String id, @Valid @RequestBody StatusRequest request,
                          Authentication authentication) {
        users.setEnabled(id, request.enabled(), authentication.getName());
    }

    @PutMapping("/admin/users/{id}/roles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceRoles(@PathVariable String id, @Valid @RequestBody RolesRequest request,
                             Authentication authentication) {
        users.replaceRoles(id, request.roles(), authentication.getName());
    }

    @PutMapping("/admin/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable String id, @Valid @RequestBody PasswordRequest request,
                              Authentication authentication) {
        users.resetPassword(id, request.password(), authentication.getName());
    }

    @PutMapping("/internal/subjects/{subject}/roles/supplier")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grantSupplierRole(@PathVariable String subject, Authentication authentication) {
        users.grantSupplierRole(subject, authentication.getName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException exception) {
        return Map.of("code", "INVALID_REQUEST", "message", exception.getMessage());
    }

    private static List<String> defaultList(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Size(min = 12, max = 200) String password,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String displayName,
            boolean emailVerified,
            @NotEmpty Set<String> roles) {}

    public record StatusRequest(boolean enabled) {}
    public record RolesRequest(@NotEmpty Set<String> roles) {}
    public record PasswordRequest(@NotBlank @Size(min = 12, max = 200) String password) {}
}
