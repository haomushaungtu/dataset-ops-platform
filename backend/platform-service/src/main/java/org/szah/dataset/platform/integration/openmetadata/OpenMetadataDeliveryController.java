package org.szah.dataset.platform.integration.openmetadata;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/openmetadata/deliveries")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
public class OpenMetadataDeliveryController {
    private final OpenMetadataDeliveryQueryService deliveries;

    public OpenMetadataDeliveryController(OpenMetadataDeliveryQueryService deliveries) {
        this.deliveries = deliveries;
    }

    @GetMapping("/{eventId}")
    OpenMetadataDeliveryView get(@PathVariable UUID eventId) {
        return deliveries.get(eventId);
    }

    @GetMapping("/failed")
    List<OpenMetadataDeliveryView> failures(@RequestParam(defaultValue = "50") int limit) {
        return deliveries.terminalFailures(limit);
    }
}
