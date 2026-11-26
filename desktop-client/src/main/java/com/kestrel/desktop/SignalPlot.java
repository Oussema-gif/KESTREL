package com.kestrel.desktop;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

final class SignalPlot {
    static final long WINDOW_MILLIS = 60_000;
    private static final long GAP_MILLIS = 5_000;

    record Point(double x, double y) {
    }

    record Plot(List<List<Point>> runs, double latest, double minimum, double maximum) {
        boolean isEmpty() {
            return runs.isEmpty();
        }
    }

    private record Reading(long time, double value) {
    }

    private SignalPlot() {
    }

    static <T> Plot from(Deque<T> history, ToLongFunction<T> timestamp, ToDoubleFunction<T> measure) {
        if (history.isEmpty()) {
            return new Plot(List.of(), 0, 0, 0);
        }

        long end = timestamp.applyAsLong(history.getLast());
        long start = end - WINDOW_MILLIS;
        List<Reading> readings = new ArrayList<>();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (T sample : history) {
            long time = timestamp.applyAsLong(sample);
            double value = measure.applyAsDouble(sample);
            if (time < start || time > end || !Double.isFinite(value)) {
                continue;
            }
            readings.add(new Reading(time, value));
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (readings.isEmpty()) {
            return new Plot(List.of(), 0, 0, 0);
        }

        List<List<Point>> runs = new ArrayList<>();
        List<Point> current = new ArrayList<>();
        long previous = readings.getFirst().time();
        // Normalize before subtracting to keep the scale finite for all finite readings.
        double scale = Math.max(Math.abs(min), Math.abs(max));
        double low = scale == 0 ? 0 : min / scale;
        double high = scale == 0 ? 0 : max / scale;

        for (Reading reading : readings) {
            if (reading.time() - previous >= GAP_MILLIS) {
                runs.add(List.copyOf(current));
                current.clear();
            }
            double x = (reading.time() - start) / (double) WINDOW_MILLIS;
            double fraction = high == low ? 0.5 : (reading.value() / scale - low) / (high - low);
            double y = 0.86 - 0.72 * fraction;
            current.add(new Point(x, y));
            previous = reading.time();
        }
        runs.add(List.copyOf(current));
        return new Plot(List.copyOf(runs), readings.getLast().value(), min, max);
    }
}
