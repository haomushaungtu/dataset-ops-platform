package org.szah.dataset.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlatformServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformServiceApplication.class, args);
    }
}
