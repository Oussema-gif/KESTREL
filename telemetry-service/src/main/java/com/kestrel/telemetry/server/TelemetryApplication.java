package com.kestrel.telemetry.server;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(TelemetrySettings.class)
public class TelemetryApplication {
    public static void main(String[] args) throws InterruptedException {
        try (var application = SpringApplication.run(TelemetryApplication.class, args)) {
            application.getBean(TelemetryServer.class).awaitTermination();
        }
    }

    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    TelemetrySimulator simulator(TelemetrySettings settings, Clock clock) {
        return new TelemetrySimulator(settings, clock);
    }

    @Bean(destroyMethod = "close")
    TelemetryServer telemetryServer(TelemetrySettings settings, TelemetrySimulator simulator, Clock clock) throws Exception {
        return new TelemetryServer(settings, simulator, new TelemetryAuth(settings.publicKey(), clock));
    }
}
