package org.szah.dataset.platform.modules.supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.szah.dataset.platform.common.api.ApiResponse;
import org.szah.dataset.platform.common.api.BusinessException;

import java.net.URI;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/supplier-applications")
public class SupplierApplicationController {
    private final SupplierApplicationService service;

    public SupplierApplicationController(SupplierApplicationService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<SupplierApplicationView>> create(
            @Valid @RequestBody CreateSupplierApplication command,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request,
            UriComponentsBuilder uriBuilder) {
        SupplierApplicationView created = service.create(
                command, authentication.getToken().getSubject(), idempotencyKey, requestId(request));
        URI location = uriBuilder.path("/api/v1/supplier-applications/{id}").build(created.id());
        return ResponseEntity.created(location)
                .eTag(etag(created.version()))
                .body(ApiResponse.of(created, requestId(request)));
    }

    @GetMapping("/{id}")
    ResponseEntity<ApiResponse<SupplierApplicationView>> get(
            @PathVariable UUID id,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        boolean operator = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_OPERATOR")
                        || authority.getAuthority().equals("ROLE_ADMIN"));
        SupplierApplicationView application = service.get(
                id, authentication.getToken().getSubject(), operator);
        return ResponseEntity.ok().eTag(etag(application.version()))
                .body(ApiResponse.of(application, requestId(request)));
    }

    @PutMapping("/{id}")
    ResponseEntity<ApiResponse<SupplierApplicationView>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSupplierApplication command,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        SupplierApplicationView result = service.update(id, command, authentication.getToken().getSubject(),
                version(ifMatch), idempotencyKey, requestId(request));
        return ok(result, requestId(request));
    }

    @PostMapping("/{id}:submit")
    ResponseEntity<ApiResponse<SupplierApplicationView>> submit(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        SupplierApplicationView result = service.submit(id, authentication.getToken().getSubject(),
                version(ifMatch), idempotencyKey, requestId(request));
        return ok(result, requestId(request));
    }

    @PostMapping("/{id}:withdraw")
    ResponseEntity<ApiResponse<SupplierApplicationView>> withdraw(
            @PathVariable UUID id,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        SupplierApplicationView result = service.withdraw(id, authentication.getToken().getSubject(),
                version(ifMatch), idempotencyKey, requestId(request));
        return ok(result, requestId(request));
    }

    @PostMapping("/{id}:approve")
    @PreAuthorize("hasRole('OPERATOR')")
    ResponseEntity<ApiResponse<SupplierApplicationView>> approve(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewRequest review,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        return decide(id, review.comment(), SupplierApplicationStatus.APPROVED, ifMatch,
                idempotencyKey, authentication, request);
    }

    @PostMapping("/{id}:return")
    @PreAuthorize("hasRole('OPERATOR')")
    ResponseEntity<ApiResponse<SupplierApplicationView>> returnForRevision(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewRequest review,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        requireComment(review.comment());
        return decide(id, review.comment(), SupplierApplicationStatus.RETURNED, ifMatch,
                idempotencyKey, authentication, request);
    }

    @PostMapping("/{id}:reject")
    @PreAuthorize("hasRole('OPERATOR')")
    ResponseEntity<ApiResponse<SupplierApplicationView>> reject(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewRequest review,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        requireComment(review.comment());
        return decide(id, review.comment(), SupplierApplicationStatus.REJECTED, ifMatch,
                idempotencyKey, authentication, request);
    }

    private ResponseEntity<ApiResponse<SupplierApplicationView>> decide(
            UUID id,
            String comment,
            SupplierApplicationStatus decision,
            String ifMatch,
            String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        requireComment(comment);
        SupplierApplicationView result = service.decide(id, authentication.getToken().getSubject(), decision,
                comment, version(ifMatch), idempotencyKey, requestId(request));
        return ok(result, requestId(request));
    }

    private ResponseEntity<ApiResponse<SupplierApplicationView>> ok(SupplierApplicationView result,
                                                                     String requestId) {
        return ResponseEntity.ok().eTag(etag(result.version())).body(ApiResponse.of(result, requestId));
    }

    private long version(String value) {
        try {
            String normalized = value.trim();
            if (normalized.startsWith("W/")) {
                normalized = normalized.substring(2);
            }
            normalized = normalized.replace("\"", "");
            return Long.parseLong(normalized);
        } catch (RuntimeException exception) {
            throw new BusinessException("INVALID_VERSION", "If-Match 必须是数值版本 ETag", BAD_REQUEST);
        }
    }

    private void requireComment(String comment) {
        if (comment == null || comment.isBlank()) {
            throw new BusinessException("REVIEW_COMMENT_REQUIRED", "退回或拒绝必须填写审核意见", BAD_REQUEST);
        }
    }

    private String requestId(HttpServletRequest request) {
        return request.getAttribute("request_id").toString();
    }

    private String etag(long version) {
        return "\"" + version + "\"";
    }

    public record CreateSupplierApplication(
            @NotBlank @Size(max = 200) String organizationName,
            @NotBlank @Pattern(regexp = "[0-9A-Z]{18}") String unifiedSocialCreditCode,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Size(max = 40) String contactPhone) {
    }

    public record UpdateSupplierApplication(
            @NotBlank @Size(max = 200) String organizationName,
            @NotBlank @Pattern(regexp = "[0-9A-Z]{18}") String unifiedSocialCreditCode,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Size(max = 40) String contactPhone) {
    }

    public record ReviewRequest(@Size(max = 2000) String comment) {
    }
}
