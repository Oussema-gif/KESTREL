package com.kestrel.telemetry.server;

import com.kestrel.telemetry.SimulationScenario;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kestrel.telemetry")
record TelemetrySettings(int port, Path publicKey, List<String> machineCodes, SimulationScenario initialScenario,
                         Duration interval, Threshold temperature, Threshold vibration, Threshold pressure,
                         Threshold energy) {
    private static final Pattern MACHINE = Pattern.compile("[A-Z0-9]+(?:-[A-Z0-9]+)*");

    TelemetrySettings {
        Objects.requireNonNull(publicKey, "Missing telemetry public key path");
        Objects.requireNonNull(initialScenario, "Missing initial simulation scenario");
        Objects.requireNonNull(temperature, "Missing temperature thresholds");
        Objects.requireNonNull(vibration, "Missing vibration thresholds");
        Objects.requireNonNull(pressure, "Missing pressure thresholds");
        Objects.requireNonNull(energy, "Missing energy thresholds");
        machineCodes = List.copyOf(machineCodes);
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Telemetry port must be between 0 and 65535.");
        }
        if (interval == null || interval.compareTo(Duration.ofMillis(50)) < 0
                || interval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Telemetry interval must be between 50ms and 1m.");
        }
        if (machineCodes.isEmpty() || machineCodes.stream().anyMatch(code -> !validMachine(code))
                || new HashSet<>(machineCodes).size() != machineCodes.size()) {
            throw new IllegalArgumentException("Configure a nonempty list of distinct, valid machine codes.");
        }
        if (initialScenario == SimulationScenario.SCENARIO_UNSPECIFIED || initialScenario == SimulationScenario.UNRECOGNIZED) {
            throw new IllegalArgumentException("Set the initial scenario to a supported simulation mode.");
        }
    }

    static boolean validMachine(String code) {
        return code.length() <= 32 && MACHINE.matcher(code).matches();
    }

    record Threshold(double warning, double critical) {
        Threshold {
            if (!Double.isFinite(warning) || !Double.isFinite(critical) || warning < 0
                    || critical <= warning || critical > Double.MAX_VALUE / 2) {
                throw new IllegalArgumentException("Telemetry thresholds must be finite and satisfy 0 <= warning < critical.");
            }
        }

        int level(double sample) {
            return sample > critical ? 2 : sample > warning ? 1 : 0;
        }

        double spike(double baseline) {
            return Math.max(baseline, critical + Math.max(1, critical * 0.12));
        }
    }
}
