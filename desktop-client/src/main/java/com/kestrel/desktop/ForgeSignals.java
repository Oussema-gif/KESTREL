package com.kestrel.desktop;

import java.util.Locale;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;

final class ForgeSignals {
    private static final PseudoClass CONNECTED = PseudoClass.getPseudoClass("connected");
    private static final PseudoClass BUSY = PseudoClass.getPseudoClass("busy");
    private static final PseudoClass UNAVAILABLE = PseudoClass.getPseudoClass("unavailable");

    private ForgeSignals() {
    }

    static Node style(Node rail) {
        rail.getStyleClass().add("forge-protocol-rail");
        styleStatus(rail);
        return rail;
    }

    private static void styleStatus(Node node) {
        if (node instanceof Label label && label.textProperty().isBound()) {
            label.getStyleClass().add("forge-connection");
            update(label, label.getText());
            label.textProperty().addListener((property, previous, text) -> update(label, text));
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(ForgeSignals::styleStatus);
        }
    }

    private static void update(Label label, String value) {
        String text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        label.pseudoClassStateChanged(CONNECTED, text.startsWith("connected"));
        label.pseudoClassStateChanged(BUSY, text.startsWith("checking") || text.startsWith("reconnecting")
                || text.startsWith("connecting") || text.startsWith("retry"));
        label.pseudoClassStateChanged(UNAVAILABLE, text.startsWith("unavailable")
                || text.startsWith("offline") || text.startsWith("down") || text.startsWith("disconnected"));
    }
}
