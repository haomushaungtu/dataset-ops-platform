package org.szah.dataset.platform.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.szah.dataset.platform.common.api.ApiResponse;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public final class CurrentUserController {
    @GetMapping("/me")
    ApiResponse<Map<String, Object>> me(JwtAuthenticationToken authentication, HttpServletRequest request) {
        String username = authentication.getToken().getClaimAsString("preferred_username");
        return ApiResponse.of(Map.of(
                "subject_id", authentication.getToken().getSubject(),
                "username", username == null ? authentication.getToken().getSubject() : username,
                "roles", roles(authentication)), request.getAttribute("request_id").toString());
    }

    private List<String> roles(JwtAuthenticationToken authentication) {
        List<String> roles = authentication.getToken().getClaimAsStringList("roles");
        return roles == null ? List.of() : roles;
    }
}
