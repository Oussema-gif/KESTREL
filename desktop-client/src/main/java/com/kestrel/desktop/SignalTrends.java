package com.kestrel.desktop;

import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

final class SignalTrends {
    private static final List<Metric> METRICS = List.of(
            new Metric("Temperature", "°C", Color.web("#d5ad80")),
            new Metric("Vibration", "mm/s", Color.web("#89b8d5")),
            new Metric("Pressure", "bar", Color.web("#a6c6a3")),
            new Metric("Energy", "kW", Color.web("#b8acd4"))
    );

    private record Metric(String label, String unit, Color color) {
    }

    private SignalTrends() {
    }

    static <T> void addTo(VBox body, Deque<T> history, ToLongFunction<T> timestamp, Function<T, double[]> values) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("signal-trends-grid");
        List<Card> cards = METRICS.stream().map(Card::new).toList();
        grid.getChildren().addAll(cards.stream().map(card -> card.node).toList());
        arrange(grid, cards, 2);
        grid.widthProperty().addListener((property, previous, width) -> {
            int columns = width.doubleValue() >= 685 ? 4 : 2;
            if (grid.getColumnConstraints().size() != columns) {
                arrange(grid, cards, columns);
            }
        });

        Label label = new Label("Signal trends");
        label.getStyleClass().add("signal-trends-title");
        Label window = new Label("Last 60 seconds");
        window.getStyleClass().add("signal-trends-window");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleRow = new HBox(10, label, spacer, window);
        titleRow.setAlignment(Pos.BASELINE_LEFT);
        VBox section = new VBox(8, titleRow, grid);
        section.getStyleClass().add("signal-trends");

        // The deque belongs to Screens and is mutated on the JavaFX application thread.
        // Redraw only when the latest sample changes, or when a canvas is resized.
        class Refresh {
            private T previous;
            private boolean rendered;

            void update() {
                T latest = history.peekLast();
                if (rendered && previous == latest) {
                    return;
                }
                rendered = true;
                previous = latest;
                for (int index = 0; index < cards.size(); index++) {
                    int metric = index;
                    cards.get(index).update(SignalPlot.from(history, timestamp,
                            sample -> values.apply(sample)[metric]));
                }
            }
        }
        var refresh = new Refresh();
        Timeline ticker = new Timeline(new KeyFrame(Duration.millis(250), event -> refresh.update()));
        ticker.setCycleCount(Animation.INDEFINITE);
        section.sceneProperty().addListener((property, previous, scene) -> {
            if (scene == null) {
                ticker.stop();
            } else {
                refresh.update();
                ticker.play();
            }
        });
        refresh.update();
        body.getChildren().add(section);
    }

    private static void arrange(GridPane grid, List<Card> cards, int columns) {
        grid.getColumnConstraints().clear();
        for (int column = 0; column < columns; column++) {
            ColumnConstraints width = new ColumnConstraints();
            width.setPercentWidth(100.0 / columns);
            grid.getColumnConstraints().add(width);
        }
        for (int index = 0; index < cards.size(); index++) {
            GridPane.setColumnIndex(cards.get(index).node, index % columns);
            GridPane.setRowIndex(cards.get(index).node, index / columns);
        }
    }

    private static final class Card {
        private static final Color GRID = Color.web("#3b4651").deriveColor(0, 1, 1, 0.55);
        private static final double INSET = 3;
        private final VBox node;
        private final Metric metric;
        private final Label number = new Label();
        private final Label range = new Label();
        private final Canvas canvas = new Canvas();
        private SignalPlot.Plot plot;

        Card(Metric metric) {
            this.metric = metric;
            Label name = new Label(metric.label());
            name.getStyleClass().add("signal-metric-name");
            number.getStyleClass().add("signal-metric-number");
            range.getStyleClass().add("signal-metric-range");

            StackPane chart = new StackPane(canvas);
            chart.getStyleClass().add("signal-chart");
            chart.setMinHeight(49);
            chart.setPrefHeight(56);
            canvas.setManaged(false);
            canvas.setMouseTransparent(true);
            canvas.widthProperty().bind(chart.widthProperty());
            canvas.heightProperty().bind(chart.heightProperty());
            canvas.widthProperty().addListener(observable -> draw());
            canvas.heightProperty().addListener(observable -> draw());
            node = new VBox(5, name, number, chart, range);
            node.getStyleClass().add("signal-card");
            node.setMaxWidth(Double.MAX_VALUE);
        }

        void update(SignalPlot.Plot data) {
            plot = data;
            if (plot.isEmpty()) {
                number.setText("—");
                range.setText("No readings");
                node.setAccessibleText(metric.label() + ", no readings");
            } else {
                number.setText(String.format(Locale.ROOT, "%.1f %s", plot.latest(), metric.unit()));
                range.setText(String.format(Locale.ROOT, "%.1f – %.1f %s",
                        plot.minimum(), plot.maximum(), metric.unit()));
                node.setAccessibleText(metric.label() + ", " + number.getText() + ", range " + range.getText());
            }
            draw();
        }

        private void draw() {
            double width = canvas.getWidth() - 2 * INSET;
            double height = canvas.getHeight() - 2 * INSET;
            if (width <= 0 || height <= 0 || plot == null) {
                return;
            }
            GraphicsContext pen = canvas.getGraphicsContext2D();
            pen.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            pen.setStroke(GRID);
            pen.setLineWidth(1);
            for (int row = 1; row <= 3; row++) {
                double y = INSET + height * row / 4;
                pen.strokeLine(INSET, y, INSET + width, y);
            }
            if (plot.isEmpty()) {
                return;
            }

            pen.setLineCap(StrokeLineCap.ROUND);
            pen.setLineWidth(1.8);
            for (List<SignalPlot.Point> run : plot.runs()) {
                if (run.size() > 1) {
                    paintRun(pen, run, width, height);
                } else {
                    dot(pen, run.getFirst(), width, height, 3);
                }
            }
            dot(pen, plot.runs().getLast().getLast(), width, height, 5);
        }

        private void paintRun(GraphicsContext pen, List<SignalPlot.Point> run, double width, double height) {
            pen.setFill(metric.color().deriveColor(0, 1, 1, 0.16));
            path(pen, run, width, height);
            pen.lineTo(INSET + run.getLast().x() * width, INSET + height);
            pen.lineTo(INSET + run.getFirst().x() * width, INSET + height);
            pen.closePath();
            pen.fill();

            pen.setStroke(metric.color());
            path(pen, run, width, height);
            pen.stroke();
        }

        private static void path(GraphicsContext pen, List<SignalPlot.Point> run, double width, double height) {
            pen.beginPath();
            SignalPlot.Point first = run.getFirst();
            pen.moveTo(INSET + first.x() * width, INSET + first.y() * height);
            for (int index = 1; index < run.size(); index++) {
                SignalPlot.Point point = run.get(index);
                pen.lineTo(INSET + point.x() * width, INSET + point.y() * height);
            }
        }

        private void dot(GraphicsContext pen, SignalPlot.Point point, double width, double height, double size) {
            pen.setFill(metric.color());
            pen.fillOval(INSET + point.x() * width - size / 2,
                    INSET + point.y() * height - size / 2, size, size);
        }
    }
}
