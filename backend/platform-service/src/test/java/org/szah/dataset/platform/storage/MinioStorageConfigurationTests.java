package org.szah.dataset.platform.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MinioStorageConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MinioStorageConfiguration.class);

    @Test
    void defaultsToDisabledStorageWithoutRequiringCredentials() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(QualificationMaterialStorage.class);
            assertThat(context.getBean(QualificationMaterialStorage.class))
                    .isInstanceOf(DisabledQualificationMaterialStorage.class);
        });
    }

    @Test
    void failsClosedWhenEnabledConfigurationIsIncomplete() {
        contextRunner.withPropertyValues("platform.storage.minio.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "platform.storage.minio.endpoint is required when MinIO is enabled");
                });
    }
}
