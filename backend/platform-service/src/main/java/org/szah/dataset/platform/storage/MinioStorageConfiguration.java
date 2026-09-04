package org.szah.dataset.platform.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioStorageProperties.class)
class MinioStorageConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "platform.storage.minio", name = "enabled", havingValue = "true")
    QualificationMaterialStorage minioQualificationMaterialStorage(MinioStorageProperties properties) {
        return new MinioQualificationMaterialStorage(properties);
    }

    @Bean
    @ConditionalOnMissingBean(QualificationMaterialStorage.class)
    QualificationMaterialStorage disabledQualificationMaterialStorage() {
        return new DisabledQualificationMaterialStorage();
    }
}
