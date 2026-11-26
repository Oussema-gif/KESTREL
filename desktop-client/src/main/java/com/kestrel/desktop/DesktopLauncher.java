package com.kestrel.desktop;

import java.io.IOException;
import java.util.List;
import javafx.application.Application;

public final class DesktopLauncher {
    private DesktopLauncher() {
    }

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length == 1 && args[0].equals("--self-check")) {
            checkRuntime();
            return;
        }
        Application.launch(KestrelDesktop.class, args);
    }

    private static void checkRuntime() throws IOException, ClassNotFoundException {
        if (Runtime.version().feature() != 21) {
            throw new IllegalStateException("Kestrel requires Java 21.");
        }
        ClassLoader loader = DesktopLauncher.class.getClassLoader();
        for (String dependency : List.of(
                "com.kestrel.desktop.KestrelDesktop",
                "javafx.scene.control.Button",
                "io.grpc.ManagedChannel",
                "com.kestrel.telemetry.TelemetryServiceGrpc",
                "com.fasterxml.jackson.databind.ObjectMapper",
                "java.net.http.HttpClient")) {
            Class.forName(dependency, false, loader);
        }
        try (var css = DesktopLauncher.class.getResourceAsStream("theme.css")) {
            if (css == null || css.read() < 0) {
                throw new IOException("The packaged desktop theme is missing or empty.");
            }
        }
        System.out.println("PASS. Kestrel launcher, embedded Java 21, client libraries and theme.");
    }
}
