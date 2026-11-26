package com.kestrel.desktop;

import java.util.List;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

final class ForgeTopBar {
    private ForgeTopBar() {
    }

    static VBox build(Node header, HBox routes, ObservableBooleanValue reduceMotion) {
        header.getStyleClass().add("forge-header");
        routes.getStyleClass().add("forge-routes");
        routes.setAlignment(Pos.CENTER_LEFT);
        // Keep the tabs at readable widths. A horizontal scrollbar handles overflow.
        routes.setMinWidth(HBox.USE_PREF_SIZE);
        var navigation = new Navigation(routes, reduceMotion);
        var top = new VBox(header, navigation.viewport);
        top.getStyleClass().add("forge-topbar");
        return top;
    }

    private static final class Navigation {
        private static final double INDICATOR_INSET = 9;
        private static final double MIN_INDICATOR_WIDTH = 12;
        private static final Interpolator EASE = Interpolator.SPLINE(0.22, 0.75, 0.24, 1);

        private final HBox routes;
        private final ObservableBooleanValue reduceMotion;
        private final Rectangle indicator = new Rectangle();
        private final StackPane track;
        private final ScrollPane viewport;
        private final InvalidationListener selection = observable -> queueUpdate(true);
        private final InvalidationListener geometry = observable -> queueUpdate(false);
        private Timeline animation;
        private ToggleButton current;
        private double targetX;
        private double targetWidth;
        private boolean queued;
        private boolean requestedMotion;

        Navigation(HBox routes, ObservableBooleanValue reduceMotion) {
            this.routes = routes;
            this.reduceMotion = reduceMotion;
            indicator.getStyleClass().add("forge-tab-indicator");
            indicator.setHeight(3);
            indicator.setArcWidth(3);
            indicator.setArcHeight(3);
            indicator.setManaged(false);
            indicator.setMouseTransparent(true);
            indicator.setVisible(false);

            track = new StackPane(routes, indicator);
            track.setAlignment(Pos.CENTER_LEFT);
            track.getStyleClass().add("forge-tab-track");

            viewport = new ScrollPane(track);
            viewport.getStyleClass().add("forge-tab-viewport");
            viewport.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            viewport.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            viewport.setFitToHeight(true);
            viewport.setFitToWidth(true);
            viewport.setPannable(false);
            viewport.setFocusTraversable(false);
            viewport.visibleProperty().bind(routes.visibleProperty());
            viewport.managedProperty().bind(routes.managedProperty());

            routes.getChildren().forEach(node -> observe(node, true));
            routes.getChildren().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) {
                    change.getRemoved().forEach(node -> observe(node, false));
                    change.getAddedSubList().forEach(node -> observe(node, true));
                }
                queueUpdate(false);
            });
            routes.layoutBoundsProperty().addListener(geometry);
            viewport.viewportBoundsProperty().addListener(geometry);
            viewport.visibleProperty().addListener(geometry);
            viewport.sceneProperty().addListener(geometry);
            reduceMotion.addListener((property, previous, enabled) -> {
                if (enabled) {
                    stopAnimation();
                    indicator.setLayoutX(targetX);
                    indicator.setWidth(targetWidth);
                }
            });
            routes.addEventFilter(KeyEvent.KEY_PRESSED, this::navigateWithKeys);
            routes.addEventFilter(ActionEvent.ACTION, event -> {
                if (event.getTarget() instanceof ToggleButton tab && current == tab && !tab.isSelected()) {
                    tab.setSelected(true);
                }
            });
            queueUpdate(false);
        }

        private void observe(Node node, boolean add) {
            if (node instanceof ToggleButton tab) {
                if (add) {
                    tab.selectedProperty().addListener(selection);
                    tab.boundsInParentProperty().addListener(geometry);
                    tab.visibleProperty().addListener(geometry);
                } else {
                    tab.selectedProperty().removeListener(selection);
                    tab.boundsInParentProperty().removeListener(geometry);
                    tab.visibleProperty().removeListener(geometry);
                }
            }
        }

        private List<ToggleButton> tabs() {
            return routes.getChildren().stream()
                    .filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast)
                    .filter(tab -> tab.isVisible() && tab.isManaged())
                    .toList();
        }

        private void queueUpdate(boolean animatedSelection) {
            requestedMotion |= animatedSelection;
            if (queued) {
                return;
            }
            queued = true;
            Platform.runLater(() -> {
                boolean animated = requestedMotion;
                queued = false;
                requestedMotion = false;
                update(animated);
            });
        }

        private void update(boolean animatedSelection) {
            ToggleButton selected = tabs().stream().filter(ToggleButton::isSelected).findFirst().orElse(null);
            if (!viewport.isVisible() || viewport.getScene() == null || selected == null || selected.getWidth() <= 0) {
                stopAnimation();
                current = null;
                indicator.setVisible(false);
                return;
            }

            Bounds bounds = selected.getBoundsInParent();
            double x = routes.getLayoutX() + bounds.getMinX() + INDICATOR_INSET;
            double width = Math.max(MIN_INDICATOR_WIDTH, bounds.getWidth() - 2 * INDICATOR_INSET);
            double y = routes.getLayoutY() + routes.getHeight() - indicator.getHeight();
            boolean changed = current != selected;
            boolean slide = changed && indicator.isVisible() && animatedSelection && !reduceMotion.get();
            current = selected;
            indicator.setLayoutY(y);
            indicator.setVisible(true);

            if (changed || Math.abs(targetX - x) > 0.25 || Math.abs(targetWidth - width) > 0.25) {
                double previousX = indicator.getLayoutX();
                double previousWidth = indicator.getWidth();
                stopAnimation();
                targetX = x;
                targetWidth = width;
                if (slide) {
                    indicator.setLayoutX(previousX);
                    indicator.setWidth(previousWidth);
                    animation = new Timeline(new KeyFrame(Duration.millis(230),
                            new KeyValue(indicator.layoutXProperty(), x, EASE),
                            new KeyValue(indicator.widthProperty(), width, EASE)));
                    animation.play();
                } else {
                    indicator.setLayoutX(x);
                    indicator.setWidth(width);
                }
            }
            if (changed) {
                reveal(bounds);
            }
        }

        private void reveal(Bounds selected) {
            double visible = viewport.getViewportBounds().getWidth();
            double range = track.getWidth() - visible;
            if (visible <= 0 || range <= 0) {
                return;
            }
            double left = routes.getLayoutX() + selected.getMinX();
            double right = routes.getLayoutX() + selected.getMaxX();
            double start = viewport.getHvalue() * range;
            if (left < start) {
                viewport.setHvalue(Math.max(0, left / range));
            } else if (right > start + visible) {
                viewport.setHvalue(Math.min(1, (right - visible) / range));
            }
        }

        private void stopAnimation() {
            if (animation != null) {
                animation.stop();
                animation = null;
            }
        }

        private void navigateWithKeys(KeyEvent event) {
            if (!(event.getTarget() instanceof ToggleButton tab)
                    || event.isAltDown() || event.isControlDown() || event.isMetaDown() || event.isShiftDown()) {
                return;
            }
            List<ToggleButton> available = tabs().stream().filter(candidate -> !candidate.isDisabled()).toList();
            int index = available.indexOf(tab);
            if (index < 0) {
                return;
            }
            int next = switch (event.getCode()) {
                case LEFT -> Math.max(0, index - 1);
                case RIGHT -> Math.min(available.size() - 1, index + 1);
                case HOME -> 0;
                case END -> available.size() - 1;
                default -> -1;
            };
            if (next < 0) {
                return;
            }
            ToggleButton target = available.get(next);
            target.requestFocus();
            if (!target.isSelected()) {
                target.fire();
            }
            event.consume();
        }
    }
}
