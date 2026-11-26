package com.kestrel.telemetry.server;

import com.kestrel.telemetry.SimulationScenario;
import com.kestrel.telemetry.TelemetryReading;
import com.kestrel.telemetry.TelemetrySnapshot;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TelemetrySimulator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TelemetrySimulator.class);

    record Frame(SimulationScenario scenario, TelemetryReading reading, String status) {
        TelemetrySnapshot snapshot() {
            if (reading == null) {
                throw Status.FAILED_PRECONDITION.withDescription("No reading has been produced for this machine.").asRuntimeException();
            }
            return TelemetrySnapshot.newBuilder().setMachineCode(reading.getMachineCode())
                    .setTemperatureC(reading.getTemperatureC()).setVibrationMmS(reading.getVibrationMmS())
                    .setPressureBar(reading.getPressureBar()).setEnergyKw(reading.getEnergyKw())
                    .setTimestampEpochMs(reading.getTimestampEpochMs()).setMachineStatus(status).build();
        }
    }

    private record Subscription(ServerCallStreamObserver<TelemetryReading> observer, Instant expiresAt) {}

    private static final class Machine {
        // The immutable frame is published to concurrent unary readers. Subscription mutations are confined to the tick thread.
        private volatile Frame frame;
        private final Set<Subscription> subscribers = new LinkedHashSet<>();

        Machine(Frame frame) {
            this.frame = frame;
        }
    }

    private final Map<String, Machine> machines;
    private final ScheduledThreadPoolExecutor scheduler;
    private final TelemetrySettings settings;
    private final Clock clock;
    private boolean stopping;

    TelemetrySimulator(TelemetrySettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        var configured = new LinkedHashMap<String, Machine>();
        for (String code : settings.machineCodes()) {
            configured.put(code, new Machine(frame(code, settings.initialScenario(), null)));
        }
        machines = Map.copyOf(configured);
        scheduler = new ScheduledThreadPoolExecutor(1, job -> {
            Thread thread = new Thread(job, "kestrel-telemetry-simulator");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = settings.interval().toMillis();
        scheduler.scheduleWithFixedDelay(this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    Frame snapshot(String code) {
        return machine(code).frame;
    }

    void subscribe(String code, ServerCallStreamObserver<TelemetryReading> observer, Instant expiresAt) {
        Machine machine = machine(code);
        var subscription = new Subscription(observer, expiresAt);
        observer.setOnCancelHandler(() -> {
            try {
                scheduler.execute(() -> machine.subscribers.remove(subscription));
            } catch (RejectedExecutionException closed) {
                // The scheduler already discarded the stream registry.
            }
        });
        submit(() -> {
            if (observer.isCancelled()) {
                return;
            }
            if (stopping) {
                observer.onError(shutdownStatus());
                return;
            }
            if (!send(subscription, machine.frame, clock.instant())) {
                return;
            }
            machine.subscribers.add(subscription);
        }, observer);
    }

    void changeScenario(String code, SimulationScenario scenario, Runnable changed, ServerCallStreamObserver<?> call) {
        Machine machine = machine(code);
        submit(() -> {
            if (call.isCancelled()) {
                return;
            }
            if (stopping) {
                call.onError(shutdownStatus());
                return;
            }
            machine.frame = frame(code, scenario, machine.frame.reading());
            changed.run();
        }, call);
    }

    private void submit(Runnable task, ServerCallStreamObserver<?> observer) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException closed) {
            if (!observer.isCancelled()) {
                observer.onError(shutdownStatus());
            }
        }
    }

    private Machine machine(String code) {
        if (!TelemetrySettings.validMachine(code)) {
            throw Status.INVALID_ARGUMENT.withDescription("Provide a valid machine code.").asRuntimeException();
        }
        Machine machine = machines.get(code);
        if (machine == null) {
            throw Status.NOT_FOUND.withDescription("This machine is not configured for telemetry.").asRuntimeException();
        }
        return machine;
    }

    private void tick() {
        if (stopping) {
            return;
        }
        try {
            Instant now = clock.instant();
            machines.forEach((code, machine) -> {
                Frame current = machine.frame;
                if (current.scenario() != SimulationScenario.OFFLINE) {
                    machine.frame = frame(code, current.scenario(), current.reading());
                }
                machine.subscribers.removeIf(subscription -> !send(subscription, machine.frame, now));
            });
        } catch (RuntimeException unexpected) {
            LOG.error("A telemetry tick failed ({})", unexpected.getClass().getSimpleName());
        }
    }

    private boolean send(Subscription subscription, Frame frame, Instant now) {
        var observer = subscription.observer();
        if (observer.isCancelled()) {
            return false;
        }
        try {
            if (!subscription.expiresAt().isAfter(now)) {
                observer.onError(Status.UNAUTHENTICATED.withDescription("The access token expired.").asRuntimeException());
                return false;
            }
            if (frame.scenario() != SimulationScenario.OFFLINE && observer.isReady()) {
                observer.onNext(frame.reading());
            }
            return true;
        } catch (RuntimeException terminatedStream) {
            return false;
        }
    }

    private Frame frame(String code, SimulationScenario scenario, TelemetryReading previous) {
        if (scenario == SimulationScenario.OFFLINE) {
            return new Frame(scenario, previous, "OFFLINE");
        }
        var random = ThreadLocalRandom.current();
        double temperature = random.nextDouble(50, 57);
        double vibration = random.nextDouble(1.8, 2.4);
        double pressure = random.nextDouble(4.0, 4.7);
        double energy = random.nextDouble(8, 9.5);

        switch (scenario) {
            case HIGH_TEMPERATURE -> temperature = settings.temperature().spike(temperature);
            case HIGH_VIBRATION -> vibration = settings.vibration().spike(vibration);
            case PRESSURE_SPIKE -> pressure = settings.pressure().spike(pressure);
            case ENERGY_SPIKE -> energy = settings.energy().spike(energy);
            case NORMAL -> { }
            default -> throw new IllegalArgumentException("Unsupported simulation scenario.");
        }

        int level = Math.max(Math.max(settings.temperature().level(temperature), settings.vibration().level(vibration)),
                Math.max(settings.pressure().level(pressure), settings.energy().level(energy)));
        String status = switch (level) {
            case 2 -> "CRITICAL";
            case 1 -> "WARNING";
            default -> "RUNNING";
        };
        long epochMillis = clock.millis();
        if (previous != null) {
            epochMillis = Math.max(epochMillis, previous.getTimestampEpochMs() + 1);
        }
        var reading = TelemetryReading.newBuilder().setMachineCode(code)
                .setTemperatureC(temperature).setVibrationMmS(vibration).setPressureBar(pressure)
                .setEnergyKw(energy).setTimestampEpochMs(epochMillis).build();
        return new Frame(scenario, reading, status);
    }

    CompletableFuture<Void> disconnect() {
        return CompletableFuture.runAsync(() -> {
            stopping = true;
            machines.values().forEach(machine -> {
                for (Subscription subscription : machine.subscribers) {
                    if (!subscription.observer().isCancelled()) {
                        try {
                            subscription.observer().onError(shutdownStatus());
                        } catch (RuntimeException terminatedStream) {
                            // The transport already closed this call.
                        }
                    }
                }
                machine.subscribers.clear();
            });
        }, scheduler);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException shutdownStatus() {
        return Status.UNAVAILABLE.withDescription("Telemetry is shutting down.").asRuntimeException();
    }
}
