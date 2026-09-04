package org.szah.dataset.platform.modules.supplier;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.szah.dataset.platform.common.api.ApiResponse;
import org.szah.dataset.platform.common.api.BusinessException;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/supplier-applications/{applicationId}/materials")
public class SupplierQualificationMaterialController {
    private final SupplierQualificationMaterialService service;

    public SupplierQualificationMaterialController(SupplierQualificationMaterialService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<SupplierQualificationMaterialService.MaterialUploadResult>> upload(
            @PathVariable UUID applicationId,
            @RequestParam("material_type") String materialType,
            @RequestPart("file") MultipartFile file,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        var result = service.upload(applicationId, materialType, file, authentication.getToken().getSubject(),
                version(ifMatch), idempotencyKey, requestId(request));
        return ResponseEntity.status(result.created() ? 201 : 200)
                .eTag(etag(result.result().applicationVersion()))
                .body(ApiResponse.of(result.result(), requestId(request)));
    }

    @GetMapping
    ApiResponse<List<SupplierQualificationMaterialView>> list(
            @PathVariable UUID applicationId,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        boolean operator = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_OPERATOR")
                        || authority.getAuthority().equals("ROLE_ADMIN"));
        return ApiResponse.of(service.list(applicationId, authentication.getToken().getSubject(), operator),
                requestId(request));
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

    private String requestId(HttpServletRequest request) {
        return request.getAttribute("request_id").toString();
    }

    private String etag(long version) {
        return "\"" + version + "\"";
    }
}
