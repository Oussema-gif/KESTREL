package com.kestrel.desktop;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.util.Duration;

final class ForgeMotion {
    private static final int MAX_PANELS = 4;
    private static final int PANEL_DURATION = 180;
    private static final int PANEL_GAP = 18;

    record Panel(Node node, double opacity) {
    }

    private ForgeMotion() {
    }

    static List<Panel> addPanelEntrances(Node page, ParallelTransition transition) {
        List<Panel> panels = new ArrayList<>(MAX_PANELS);
        collect(page, 0, panels);
        for (int index = 0; index < panels.size(); index++) {
            Panel panel = panels.get(index);
            var fade = new FadeTransition(Duration.millis(PANEL_DURATION), panel.node());
            fade.setFromValue(panel.opacity() * 0.78);
            fade.setToValue(panel.opacity());
            fade.setDelay(Duration.millis(index * PANEL_GAP));
            fade.setInterpolator(Interpolator.EASE_OUT);
            transition.getChildren().add(fade);
        }
        return panels;
    }

    static void restorePanels(List<Panel> panels) {
        panels.forEach(panel -> panel.node().setOpacity(panel.opacity()));
    }

    private static void collect(Node node, int depth, List<Panel> panels) {
        if (depth > 2 || panels.size() >= MAX_PANELS || !node.isVisible() || !node.isManaged()) {
            return;
        }
        if (depth > 0 && node.getStyleClass().contains("panel") && !node.opacityProperty().isBound()) {
            panels.add(new Panel(node, node.getOpacity()));
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, depth + 1, panels);
            }
        }
    }
}
