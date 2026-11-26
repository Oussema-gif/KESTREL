package com.kestrel.desktop;

import java.util.HashMap;
import java.util.Map;

final class TelemetryFreshness {
    static final long STALE_AFTER_MILLIS = 5_000;
    private static final long MAX_FUTURE_SKEW_MILLIS = 2_000;

    enum State { EMPTY, FRESH, STALE, OFFLINE }

    record Status(State state, long ageMillis) { }

    private record Reading(long timestamp, boolean offline) { }

    private final Map<String, Reading> readings = new HashMap<>();

    synchronized boolean observe(String machineCode, long timestamp, boolean offline, long now) {
        if (machineCode == null || machineCode.isBlank()) {
            return false;
        }

        Reading previous = readings.get(machineCode);
        if (offline) {
            if (previous == null || timestamp == 0 || timestamp >= previous.timestamp()) {
                long lastReading = previous == null ? 0 : previous.timestamp();
                readings.put(machineCode, new Reading(lastReading, true));
            }
            return false;
        }

        if (timestamp <= 0
                || timestamp > now + MAX_FUTURE_SKEW_MILLIS
                || (previous != null && timestamp <= previous.timestamp())) {
            return false;
        }

        long age = now - timestamp;
        if (previous != null && previous.offline() && age >= STALE_AFTER_MILLIS) {
            return false;
        }

        readings.put(machineCode, new Reading(timestamp, false));
        return age < STALE_AFTER_MILLIS;
    }

    synchronized Status status(String machineCode, long now) {
        Reading reading = readings.get(machineCode);
        if (reading == null) {
            return new Status(State.EMPTY, -1);
        }

        long age = reading.timestamp() == 0 ? -1 : Math.max(0, now - reading.timestamp());
        if (reading.offline()) {
            return new Status(State.OFFLINE, age);
        }
        return new Status(age >= STALE_AFTER_MILLIS ? State.STALE : State.FRESH, age);
    }

    synchronized void clear() {
        readings.clear();
    }
}
