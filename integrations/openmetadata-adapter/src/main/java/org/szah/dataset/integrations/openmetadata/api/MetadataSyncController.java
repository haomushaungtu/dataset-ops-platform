package org.szah.dataset.integrations.openmetadata.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.szah.dataset.integrations.openmetadata.sync.DatasetVersionMetadata;
import org.szah.dataset.integrations.openmetadata.sync.MetadataSyncResult;
import org.szah.dataset.integrations.openmetadata.sync.OpenMetadataGateway;

@RestController
@RequestMapping("/api/v1/openmetadata")
public class MetadataSyncController {
    private final OpenMetadataGateway gateway;

    public MetadataSyncController(OpenMetadataGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/dataset-versions:upsert")
    @ResponseStatus(HttpStatus.OK)
    MetadataSyncResult upsert(@Valid @RequestBody DatasetVersionMetadata command) {
        return gateway.upsertDatasetVersion(command);
    }
}
