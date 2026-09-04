package org.szah.dataset.platform.modules.supplier;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.szah.dataset.platform.common.api.ApiResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/supplier-applications/{applicationId}/history")
public final class SupplierApplicationHistoryController {
    private final SupplierApplicationHistoryService service;

    public SupplierApplicationHistoryController(SupplierApplicationHistoryService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<SupplierApplicationHistoryView>> list(
            @PathVariable UUID applicationId,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        boolean operator = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_OPERATOR")
                        || authority.getAuthority().equals("ROLE_ADMIN"));
        return ApiResponse.of(service.list(applicationId, authentication.getToken().getSubject(), operator),
                requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return request.getAttribute("request_id").toString();
    }
}
