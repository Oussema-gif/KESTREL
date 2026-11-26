package com.kestrel.desktop;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.util.Duration;

final class Motion {
    private static final Duration PAGE_DURATION = Duration.millis(240);
    private static final Interpolator NAVIGATION_EASE = Interpolator.SPLINE(0.22, 0.75, 0.24, 1);

    private Motion() {
    }

    static void enter(Node page, ObservableBooleanValue reduceMotion) {
        if (!reduceMotion.get()) {
            new Entrance(page, reduceMotion).attach();
        }
    }

    private static final class Entrance {
        private final Node page;
        private final ObservableBooleanValue reduceMotion;
        private final ParallelTransition animation;
        private final java.util.List<ForgeMotion.Panel> panels;
        private final ChangeListener<Scene> sceneListener;
        private final ChangeListener<Boolean> preferenceListener;

        Entrance(Node page, ObservableBooleanValue reduceMotion) {
            this.page = page;
            this.reduceMotion = reduceMotion;
            var fade = new FadeTransition(PAGE_DURATION, page);
            fade.setFromValue(0.92);
            fade.setToValue(1);
            fade.setInterpolator(NAVIGATION_EASE);
            var slide = new TranslateTransition(PAGE_DURATION, page);
            slide.setFromY(6);
            slide.setToY(0);
            slide.setInterpolator(NAVIGATION_EASE);
            animation = new ParallelTransition(fade, slide);
            panels = ForgeMotion.addPanelEntrances(page, animation);
            animation.setOnFinished(event -> finish());
            sceneListener = (property, previous, current) -> {
                if (current == null) {
                    finish();
                } else {
                    start();
                }
            };
            preferenceListener = (property, previous, current) -> {
                if (current) {
                    finish();
                }
            };
        }

        void attach() {
            page.sceneProperty().addListener(sceneListener);
            if (page.getScene() != null) {
                start();
            }
            // Observe the shared setting only once attached. A page discarded before
            // mounting can otherwise stay reachable through the settings property.
        }

        private void start() {
            if (reduceMotion.get()) {
                finish();
                return;
            }
            reduceMotion.addListener(preferenceListener);
            animation.playFromStart();
        }

        private void finish() {
            animation.stop();
            ForgeMotion.restorePanels(panels);
            page.setTranslateY(0);
            page.setOpacity(1);
            page.sceneProperty().removeListener(sceneListener);
            reduceMotion.removeListener(preferenceListener);
        }
    }
}
