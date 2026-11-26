package com.kestrel.desktop;

import java.util.function.Supplier;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

final class TelemetryStatus {
    private TelemetryStatus() { }

    static void addTo(VBox body, Supplier<String> machine, TelemetryFreshness freshness) {
        Label label = new Label();
        label.getStyleClass().add("telemetry-freshness");
        body.getStyleClass().add("telemetry-freshness-scope");
        Runnable update = () -> {
            String code = machine.get();
            var status = freshness.status(code, System.currentTimeMillis());
            String message = switch (status.state()) {
                case EMPTY -> "NO DATA";
                case FRESH -> "LIVE";
                case STALE -> "STALE";
                case OFFLINE -> "OFFLINE";
            };
            String machineLabel = code == null || code.isBlank() ? "" : " · " + code;
            String age = status.ageMillis() < 0 ? "" : " · Last reading "
                    + status.ageMillis() / 1_000 + " s ago";
            label.setText(message + machineLabel + age);

            for (var state : TelemetryFreshness.State.values()) {
                var pseudoClass = PseudoClass.getPseudoClass(
                        state.name().toLowerCase(java.util.Locale.ROOT));
                boolean active = status.state() == state;
                label.pseudoClassStateChanged(pseudoClass, active);
                body.pseudoClassStateChanged(pseudoClass, active);
            }
        };

        Timeline clock = new Timeline(new KeyFrame(Duration.millis(500), event -> update.run()));
        clock.setCycleCount(Animation.INDEFINITE);
        label.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                clock.stop();
            } else {
                update.run();
                clock.play();
            }
        });
        update.run();
        body.getChildren().add(label);
    }
}
