package com.kestrel.maintenance;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MaintenanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MaintenanceApplication.class, args);
    }

    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }
}
