package org.szah.dataset.integrations.openmetadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.szah.dataset.integrations.openmetadata.config.AdapterProperties;

@SpringBootApplication
@EnableConfigurationProperties(AdapterProperties.class)
public class OpenMetadataAdapterApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpenMetadataAdapterApplication.class, args);
    }
}
