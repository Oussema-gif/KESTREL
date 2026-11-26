package com.kestrel.desktop;

import static com.kestrel.desktop.TelemetryFreshness.State.*;

public final class TelemetryFreshnessSmoke {
    public static void main(String[] args) {
        String machine = "MACHINE-001";
        String other = "MACHINE-002";
        long start = 10_000;
        var data = new TelemetryFreshness();

        check(data.status(machine, start).state() == EMPTY, "empty before first reading");
        check(data.observe(machine, start, false, start), "accept first fresh reading");
        check(data.status(machine, start + 4_999).state() == FRESH, "fresh before timeout");
        check(data.status(machine, start + 5_000).state() == STALE, "stale at timeout");
        check(data.status(machine, start + 5_000).ageMillis() == 5_000, "show reading age");
        check(!data.observe(machine, start, false, 15_000), "reject duplicate timestamp");
        check(!data.observe(machine, start - 1, false, 15_000), "reject older timestamp");
        check(!data.observe(machine, 18_000, false, 15_000), "reject far-future timestamp");
        check(data.status(machine, 15_000).state() == STALE, "rejected data stays stale");
        check(data.status(other, 15_000).state() == EMPTY, "machine state is independent");
        check(data.observe(machine, 15_000, false, 15_000), "accept recovery sample");
        check(data.status(machine, 15_000).state() == FRESH, "recovery restores live status");
        check(!data.observe(machine, 15_000, true, 15_001), "offline is not a new sample");
        check(data.status(machine, 15_001).state() == OFFLINE, "explicit offline");
        check(!data.observe(machine, 15_000, false, 15_002), "cached reading cannot clear offline");
        check(!data.observe(machine, 15_001, false, 20_001), "stale reading cannot clear offline");
        check(data.status(machine, 20_001).state() == OFFLINE, "offline persists until recovery");
        check(data.observe(machine, 20_002, false, 20_002), "accept fresh post-offline sample");
        check(data.status(machine, 20_002).state() == FRESH, "online after fresh sample");
        data.clear();
        check(data.status(machine, 20_003).state() == EMPTY, "clear on session end");
        System.out.println("PASS. Telemetry freshness, timeout, offline, and recovery.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
