package com.kestrel.desktop;

import java.util.Locale;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;

final class ScreenFeedback {
    private static final PseudoClass LOADING = PseudoClass.getPseudoClass("loading");
    private static final PseudoClass EMPTY = PseudoClass.getPseudoClass("empty");
    private static final PseudoClass FAILED = PseudoClass.getPseudoClass("failed");

    private ScreenFeedback() {
    }

    static Label label(String text) {
        return track(new Label(text));
    }

    static Label track(Label label) {
        label.getStyleClass().add("kestrel-feedback");
        update(label, label.getText());
        label.textProperty().addListener((property, previous, current) -> update(label, current));
        return label;
    }

    private static void update(Label label, String message) {
        String text = message == null ? "" : message.strip().toLowerCase(Locale.ROOT);
        label.pseudoClassStateChanged(LOADING,
                text.startsWith("loading") || text.startsWith("connecting"));
        label.pseudoClassStateChanged(EMPTY,
                text.startsWith("no records") || text.startsWith("no matching records")
                        || text.startsWith("registry empty"));
        label.pseudoClassStateChanged(FAILED,
                text.startsWith("request failed") || text.startsWith("registry unavailable")
                        || text.startsWith("failed to load"));
    }
}
