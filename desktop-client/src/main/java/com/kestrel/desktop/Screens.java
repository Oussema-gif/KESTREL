package com.kestrel.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.kestrel.telemetry.SimulationScenario;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableStringValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.util.Duration;

final class Screens {

    javafx.beans.value.ObservableBooleanValue motionPreference() {
        return reduceMotion;
    }

    private final TelemetryFreshness telemetryFreshness = new TelemetryFreshness();
    private static final String BEARING = "00000000-0000-4000-8000-000000000101";
    private static final int PAGE_SIZE = 20;
    private static final int HISTORY_SIZE = 90;
    private static final int EVENT_LIMIT = 120;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final String[] METRICS = {"Temperature · °C", "Vibration · mm/s", "Pressure · bar", "Power · kW"};
    private static final double[] WARNING = {75, 4, 6, 12};
    private static final double[] CRITICAL = {85, 7, 7, 15};

    @FunctionalInterface
    interface Access {
        String token() throws Exception;
    }

    @FunctionalInterface
    private interface Call<T> {
        T execute(String token) throws Exception;
    }

    private record Sample(long time, double[] values) {}
    private record OrderDraft(String title, String description) {}

    private final Gateways gateways;
    private final Workflow workflow;
    private final Executor worker;
    private final Consumer<String> selectMachine;
    private final Supplier<String> selectedMachine;
    private final ObservableStringValue readings;
    private final ObservableStringValue freshness;
    private final ObservableStringValue stock;
    private final SimpleStringProperty role = new SimpleStringProperty("");
    private final BooleanProperty reduceMotion = new SimpleBooleanProperty();
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private final ObservableList<String> activity = FXCollections.observableArrayList();
    private final Map<String, String> protocolStates = new java.util.HashMap<>();
    private final List<Plot> plots = new ArrayList<>();
    private Access access;
    private Pulse pulse;
    private Timeline alertRefresh;
    private FadeTransition transition;
    private Runnable workOrderRoleRefresh;
    private final javafx.beans.value.ChangeListener<String> workOrderRoleListener = (observable, old, value) -> {
        if (workOrderRoleRefresh != null) {
            workOrderRoleRefresh.run();
        }
    };

    Screens(Gateways gateways, Executor worker, Consumer<String> selectMachine,
            Supplier<String> selectedMachine, ObservableStringValue readings,
            ObservableStringValue freshness, ObservableStringValue stock) {
        this.gateways = gateways;
        this.worker = worker;
        this.selectMachine = selectMachine;
        this.selectedMachine = selectedMachine;
        this.readings = readings;
        this.freshness = freshness;
        this.stock = stock;
        this.workflow = new Workflow(gateways, worker, this::event);
    }

    void begin(Access authenticated) {
        end();
        access = authenticated;
        workflow.begin(authenticated);
    }

    void end() {
        telemetryFreshness.clear();
        workflow.end();
        access = null;
        role.set("");
        activity.clear();
        protocolStates.clear();
        machineChanged();
        leaveView();
    }

    void role(String value) {
        role.set(value);
    }

    void machineChanged() {
        history.clear();
        plots.forEach(Plot::clear);
        if (pulse != null) {
            pulse.update(null);
        }
    }

    void sample(long timestamp, double temperature, double vibration, double pressure,
                double power, boolean offline) {
        if (!offline && !(Double.isFinite(temperature) && Double.isFinite(vibration)
                && Double.isFinite(pressure) && Double.isFinite(power))) {
            return;
        }
        boolean phaseEightFreshSample = telemetryFreshness.observe(
                selectedMachine.get(), timestamp, offline, System.currentTimeMillis());
        if (!offline && (history.isEmpty() || timestamp > history.getLast().time())) {
            var frame = new Sample(timestamp, new double[]{temperature, vibration, pressure, power});
            history.addLast(frame);
            if (phaseEightFreshSample) {
                workflow.sample(selectedMachine.get(), timestamp, frame.values());
            }
            if (history.size() > HISTORY_SIZE) {
                history.removeFirst();
            }
            plots.forEach(plot -> plot.setData(history));
        }
        if (pulse != null) {
            pulse.update(offline ? null : history.peekLast());
        }
    }

    void protocol(String name, String value) {
        if (access == null) {
            return;
        }
        String state = value.split(" · ", 2)[0];
        if (!state.equals(protocolStates.put(name, state))) {
            event(name, state);
        }
    }

    Node page(String route) {
        leaveView();
        VBox content = new VBox(16, heading(route));
        content.setPadding(new Insets(24));
        switch (route) {
            case "Command Deck" -> commandDeck(content);
            case "Live Telemetry" -> telemetry(content);
            case "Alerts" -> alerts(content);
            case "Work Orders" -> orders(content);
            case "Inventory" -> inventory(content);
            case "Orchestration" -> timeline(content);
            case "Settings" -> settings(content);
            default -> throw new IllegalArgumentException("Unknown desktop route");
        }
        ScrollPane page = new ScrollPane(content);
        page.setFitToWidth(true);
        page.getStyleClass().add("forge-scroll");
        Motion.enter(content, reduceMotion);
        return page;
    }

    private void leaveView() {
        role.removeListener(workOrderRoleListener);
        workOrderRoleRefresh = null;
        if (alertRefresh != null) {
            alertRefresh.stop();
            alertRefresh = null;
        }
        if (transition != null) {
            transition.stop();
            transition = null;
        }
        plots.clear();
        pulse = null;
    }

    private void commandDeck(VBox body) {
        TelemetryStatus.addTo(body, selectedMachine, telemetryFreshness);
        SignalTrends.addTo(body, history, Sample::time, Sample::values);
        FlowPane machines = new FlowPane(8, 8);
        machines.getStyleClass().add("floor-grid");
        Label registry = ScreenFeedback.label("Loading the Maintenance registry…");
        call("REST", "List machines", token -> gateways.maintenance.list("machines", 0, 100, "", token),
                result -> {
                    JsonNode rows = result.path("content");
                    registry.setText(rows.isEmpty() ? "Registry empty · select a simulator code" : "Maintenance registry");
                    if (rows.isEmpty()) {
                        for (String code : List.of("MACHINE-001", "MACHINE-002", "MACHINE-003")) {
                            machineButton(machines, code, code);
                        }
                    } else {
                        rows.forEach(row -> machineButton(machines, row.path("machineCode").asText(),
                                row.path("machineCode").asText() + " · " + row.path("name").asText()));
                    }
                }, registry, () -> {});
        body.getChildren().add(panel("Factory floor", registry, machines));

        pulse = new Pulse();
        pulse.update(history.peekLast());
        VBox signals = panel("Machine instrument", bound(freshness), bound(readings));
        HBox instrument = new HBox(20, pulse, signals);
        instrument.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(signals, Priority.ALWAYS);
        body.getChildren().add(instrument);

        TableView<JsonNode> recent = table("Machine", "machineCode", "Metric", "metricType",
                "Severity", "severity", "Status", "status");
        recent.setPrefHeight(200);
        Label feedStatus = new Label();
        var loading = new SimpleBooleanProperty();
        Runnable load = () -> {
            if (loading.get()) {
                return;
            }
            loading.set(true);
            call(null, "Refresh active alerts", token -> gateways.maintenance.list("alerts", 0, 5, "ACTIVE", token),
                    result -> {
                        recent.getItems().setAll(rows(result));
                        feedStatus.setText(recent.getItems().isEmpty() ? "No active alerts" : "Latest active alerts");
                    }, feedStatus, () -> loading.set(false));
        };
        load.run();
        alertRefresh = new Timeline(new KeyFrame(Duration.seconds(8), event -> load.run()));
        alertRefresh.setCycleCount(Timeline.INDEFINITE);
        alertRefresh.play();
        body.getChildren().add(panel("Alert feed", feedStatus, recent, bound(stock)));
    }

    private void machineButton(FlowPane pane, String code, String caption) {
        Button machine = button(caption, () -> selectMachine.accept(code));
        machine.getStyleClass().add("machine-node");
        pane.getChildren().add(machine);
    }

    private void telemetry(VBox body) {
        TelemetryStatus.addTo(body, selectedMachine, telemetryFreshness);
        body.getChildren().add(panel("Selected machine", bound(freshness), bound(readings),
                new Label("Threshold lines use the documented demo defaults.")));
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        for (int metric = 0; metric < METRICS.length; metric++) {
            Plot plot = new Plot(metric);
            plots.add(plot);
            plot.setData(history);
            VBox card = panel(METRICS[metric], plot.chart);
            card.setMinWidth(0);
            GridPane.setHgrow(card, Priority.ALWAYS);
            grid.add(card, metric % 2, metric / 2);
        }
        body.getChildren().add(grid);
    }

    private void alerts(VBox body) {
        Pager page = new Pager("alerts",
                table("Machine", "machineCode", "Metric", "metricType", "Severity", "severity",
                        "Status", "status", "Occurred", "occurredAt"),
                "ACTIVE", "ACKNOWLEDGED", "RESOLVED");
        Button acknowledge = button("Acknowledge", () -> alertAction(page, "acknowledge"));
        Button resolve = button("Resolve", () -> alertAction(page, "resolve"));
        acknowledge.disableProperty().bind(Bindings.createBooleanBinding(() ->
                !operator() || page.busy.get() || !"ACTIVE".equals(page.selectedStatus()),
                role, page.busy, page.table.getSelectionModel().selectedItemProperty()));
        resolve.disableProperty().bind(Bindings.createBooleanBinding(() ->
                !operator() || page.busy.get()
                        || !List.of("ACTIVE", "ACKNOWLEDGED").contains(page.selectedStatus()),
                role, page.busy, page.table.getSelectionModel().selectedItemProperty()));
        Label evidence = new Label("Select an alert to inspect its evidence.");
        evidence.setWrapText(true);
        page.table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                evidence.setText(selected == null ? "Select an alert to inspect its evidence."
                        : selected.path("message").asText() + " · observed " + selected.path("observedValue").asText()
                        + " · threshold " + selected.path("thresholdValue").asText()));
        body.getChildren().addAll(page.view(), panel("Alert evidence", evidence, new HBox(8, acknowledge, resolve)));
        page.load();
    }

    private void alertAction(Pager page, String action) {
        JsonNode selected = page.table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            page.perform("Alert " + action, token -> gateways.maintenance.patch(
                    "alerts/" + uuid(selected.path("id").asText()) + "/" + action, null, token));
        }
    }

    private void orders(VBox body) {
        Pager page = new Pager("work-orders",
                table("Machine", "machineCode", "Title", "title", "Priority", "priority",
                        "Status", "status", "Reservation", "reservationStatus"),
                "OPEN", "CHECKING_INVENTORY", "WAITING_FOR_PARTS", "PENDING_RESERVATION",
                "READY_FOR_MAINTENANCE", "IN_PROGRESS", "COMPLETED", "CANCELLED");
        ComboBox<String> actions = new ComboBox<>();
        actions.setPromptText("Select a transition");
        Button apply = button("Apply", () -> {
            JsonNode order = page.table.getSelectionModel().getSelectedItem();
            String next = actions.getValue();
            if (order != null && transitions(order).contains(next)) {
                page.perform("Work order → " + next, token -> gateways.maintenance.patch(
                        "work-orders/" + uuid(order.path("id").asText()) + "/status", Map.of("status", next), token));
            }
        });
        Runnable refreshActions = () -> actions.getItems().setAll(transitions(page.table.getSelectionModel().getSelectedItem()));
        page.table.getSelectionModel().selectedItemProperty().addListener((observable, old, value) -> refreshActions.run());
        role.addListener(workOrderRoleListener);
        workOrderRoleRefresh = refreshActions;
        refreshActions.run();
        apply.disableProperty().bind(page.busy.or(actions.valueProperty().isNull()));

        Button create = button("New for selected machine", () -> draft().ifPresent(draft -> {
            String code = selectedMachine.get();
            page.perform("Create work order", token -> {
                JsonNode matched = gateways.maintenance.list("machines", 0, 1, "", code, token).path("content").path(0);
                if (matched.isMissingNode()) {
                    throw new Gateways.Rejected(404, "Register " + code + " in Maintenance before creating an order.");
                }
                return gateways.maintenance.create("work-orders", Map.of(
                        "machineId", uuid(matched.path("id").asText()), "machineCode", code,
                        "title", draft.title(), "description", draft.description(), "priority", "MEDIUM"), token);
            });
        }));
        create.disableProperty().bind(Bindings.createBooleanBinding(() -> !operator() || page.busy.get(), role, page.busy));

        Button assign = button("Assign technician", () -> assign(page));
        assign.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            String value = page.selectedStatus();
            return !operator() || page.busy.get() || value.isEmpty()
                    || List.of("COMPLETED", "CANCELLED").contains(value);
        }, role, page.busy, page.table.getSelectionModel().selectedItemProperty()));

        Label detail = new Label("Select a work order to view its details.");
        detail.setWrapText(true);
        page.table.getSelectionModel().selectedItemProperty().addListener((observable, old, order) ->
                detail.setText(order == null ? "Select a work order to view its details."
                        : order.path("description").asText() + "\nTechnician: " + order.path("technicianId").asText("Unassigned")
                        + "\nReservation: " + order.path("reservationId").asText("None")));

        body.getChildren().addAll(page.view(),
                panel("Work order details", detail, new FlowPane(8, 8, create, assign, actions, apply)));
        page.load();
    }

    private List<String> transitions(JsonNode order) {
        if (order == null) {
            return List.of();
        }
        String status = order.path("status").asText();
        boolean reserved = "RESERVED".equals(order.path("reservationStatus").asText());
        boolean needsPart = order.hasNonNull("partRequirement");
        boolean canStart = order.hasNonNull("technicianId") && (!needsPart || reserved);
        if ("TECHNICIAN".equals(role.get())) {
            return switch (status) {
                case "READY_FOR_MAINTENANCE" -> canStart ? List.of("IN_PROGRESS") : List.of();
                case "IN_PROGRESS" -> List.of("COMPLETED");
                default -> List.of();
            };
        }
        if (!operator()) {
            return List.of();
        }
        var choices = new ArrayList<>(switch (status) {
            case "OPEN" -> needsPart ? List.of("CHECKING_INVENTORY") : List.of("READY_FOR_MAINTENANCE");
            case "CHECKING_INVENTORY" -> List.of("WAITING_FOR_PARTS", "PENDING_RESERVATION");
            case "WAITING_FOR_PARTS", "PENDING_RESERVATION" -> List.of("CHECKING_INVENTORY");
            case "IN_PROGRESS" -> List.of("COMPLETED");
            default -> List.<String>of();
        });
        if ("READY_FOR_MAINTENANCE".equals(status)) {
            if (canStart) {
                choices.add("IN_PROGRESS");
            } else if (needsPart && !reserved) {
                choices.add("CHECKING_INVENTORY");
            }
        }
        if (!reserved && !List.of("COMPLETED", "CANCELLED").contains(status)) {
            choices.add("CANCELLED");
        }
        return choices;
    }

    private void assign(Pager page) {
        JsonNode order = page.table.getSelectionModel().getSelectedItem();
        if (order == null) {
            return;
        }
        page.busy.set(true);
        call("REST", "List active technicians", token -> gateways.maintenance.list("technicians", 0, 100, "", token),
                result -> {
                    var labels = new java.util.LinkedHashMap<String, String>();
                    rows(result).stream().filter(row -> row.path("active").asBoolean())
                            .forEach(row -> labels.put(row.path("fullName").asText() + " · "
                                    + row.path("id").asText(), row.path("id").asText()));
                    if (labels.isEmpty()) {
                        page.status.setText("No active technicians");
                        return;
                    }
                    var choices = new ArrayList<>(labels.keySet());
                    var dialog = new ChoiceDialog<>(choices.getFirst(), choices);
                    dialog.setTitle("Assign technician");
                    dialog.setHeaderText(order.path("title").asText());
                    dialog.setContentText("Technician");
                    dialog.showAndWait().ifPresent(name -> page.perform("Assign technician",
                            token -> gateways.maintenance.patch("work-orders/" + uuid(order.path("id").asText()) + "/assign",
                                    Map.of("technicianId", uuid(labels.get(name))), token)));
                }, page.status, () -> page.busy.set(false));
    }

    private static Optional<OrderDraft> draft() {
        Dialog<OrderDraft> dialog = new Dialog<>();
        dialog.setTitle("New work order");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField title = new TextField();
        TextArea description = new TextArea();
        description.setPrefRowCount(4);
        dialog.getDialogPane().setContent(new VBox(8, new Label("Title"), title, new Label("Description"), description));
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(Bindings.createBooleanBinding(
                () -> title.getText().isBlank() || title.getText().length() > 160
                        || description.getText().isBlank() || description.getText().length() > 2000,
                title.textProperty(), description.textProperty()));
        dialog.setResultConverter(type -> type == ButtonType.OK
                ? new OrderDraft(title.getText().strip(), description.getText().strip()) : null);
        return dialog.showAndWait();
    }

    private void inventory(VBox body) {
        TextField code = new TextField(BEARING);
        code.setAccessibleText("Inventory part UUID");
        code.setPrefColumnCount(38);
        Label result = new Label("Enter a part UUID or load the demo bearing.");
        result.setWrapText(true);
        Label details = new Label();
        details.setWrapText(true);
        ProgressBar stockTank = new ProgressBar(0);
        stockTank.setMaxWidth(Double.MAX_VALUE);
        stockTank.setVisible(false);
        stockTank.managedProperty().bind(stockTank.visibleProperty());
        var busy = new SimpleBooleanProperty();
        Runnable lookup = () -> {
            String id;
            try {
                id = uuid(code.getText().strip());
            } catch (IllegalArgumentException invalid) {
                result.setText("Enter a valid part UUID.");
                return;
            }
            busy.set(true);
            stockTank.setVisible(false);
            details.setText("");
            result.setText("Reading Inventory…");
            call("SOAP", "Get spare part", token -> gateways.inventory.part(id, token), part -> {
                int scale = Math.max(1, Math.max(part.available(), part.reorderLevel()));
                stockTank.setProgress((double) part.available() / scale);
                stockTank.setVisible(true);
                details.setText(part.sku() + " · " + part.name() + "\n" + part.available() + " " + part.unit()
                        + " available · reorder at " + part.reorderLevel() + "\n" + part.description());
                result.setText("Stock confirmed by Inventory");
            }, result, () -> busy.set(false));
        };
        Button load = button("Lookup part", lookup);
        load.disableProperty().bind(busy);
        body.getChildren().add(panel("Spare parts", new FlowPane(8, 8, code, load),
                result, details, stockTank));
        lookup.run();
    }

    private void timeline(VBox body) {
        var list = new ListView<>(activity);
        list.setPrefHeight(440);
        list.setPlaceholder(new Label("Protocol events will appear as requests complete."));
        body.getChildren().addAll(panel("Automation", workflow.controls(selectedMachine, role)),
                panel("Protocol activity", list));
    }

    private void settings(VBox body) {
        var scenario = new ComboBox<SimulationScenario>();
        scenario.getItems().setAll(SimulationScenario.NORMAL, SimulationScenario.HIGH_TEMPERATURE,
                SimulationScenario.HIGH_VIBRATION, SimulationScenario.PRESSURE_SPIKE,
                SimulationScenario.ENERGY_SPIKE, SimulationScenario.OFFLINE);
        scenario.setPromptText("Select simulation scenario");
        Label feedback = new Label("Choose a machine in the top bar.");
        feedback.setWrapText(true);
        var busy = new SimpleBooleanProperty();
        Button apply = button("Send scenario", () -> {
            String machine = selectedMachine.get();
            SimulationScenario chosen = scenario.getValue();
            busy.set(true);
            feedback.setText("Sending simulation request…");
            call("gRPC", "Apply scenario", token -> gateways.telemetry.setScenario(machine, chosen, token),
                    confirmed -> feedback.setText(confirmed.getMachineCode() + " · " + confirmed.getScenario()),
                    feedback, () -> busy.set(false));
        });
        apply.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !operator() || busy.get() || scenario.getValue() == null, role, busy, scenario.valueProperty()));
        CheckBox motion = new CheckBox("Reduce motion");
        motion.selectedProperty().bindBidirectional(reduceMotion);
        body.getChildren().addAll(panel("Simulation control", new HBox(8, scenario, apply), feedback,
                new Label("Changing scenarios requires an administrator or Maintenance operator.")), panel("Display", motion));
    }

    private <T> void call(String protocol, String operation, Call<T> request, Consumer<T> success,
                          Label feedback, Runnable finished) {
        Access captured = access;
        if (captured == null) {
            finished.run();
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    T reply = request.execute(captured.token());
                    Platform.runLater(() -> {
                        if (access == captured) {
                            finished.run();
                            if (protocol != null) {
                                event(protocol, operation + " · confirmed");
                            }
                            success.accept(reply);
                        }
                    });
                } catch (Exception failure) {
                    String message = failure instanceof Gateways.Rejected rejected
                            ? rejected.getMessage()
                            : "Request failed. Check the protocol connection and retry.";
                    Platform.runLater(() -> {
                        if (access == captured) {
                            feedback.setText(message);
                            finished.run();
                            if (protocol != null) {
                                event(protocol, operation + " · failed");
                            }
                        }
                    });
                }
            });
        } catch (RejectedExecutionException closing) {
            finished.run();
        }
    }

    private void event(String protocol, String description) {
        activity.addFirst(TIME.format(Instant.now()) + "   " + protocol + "   " + description);
        if (activity.size() > EVENT_LIMIT) {
            activity.removeLast();
        }
    }

    private boolean operator() {
        return List.of("ADMIN", "MAINTENANCE_OPERATOR").contains(role.get());
    }

    private static String uuid(String value) {
        return UUID.fromString(value).toString();
    }

    private static List<JsonNode> rows(JsonNode response) {
        var result = new ArrayList<JsonNode>();
        response.path("content").forEach(result::add);
        return result;
    }

    private static TableView<JsonNode> table(String... columns) {
        TableView<JsonNode> table = new TableView<>();
        table.setPlaceholder(ScreenFeedback.label("No records to display"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(290);
        for (int i = 0; i < columns.length; i += 2) {
            var column = new TableColumn<JsonNode, String>(columns[i]);
            String field = columns[i + 1];
            column.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().path(field).asText("—")));
            table.getColumns().add(column);
        }
        return table;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("heading");
        return label;
    }

    private static Label bound(ObservableStringValue value) {
        Label label = new Label();
        label.textProperty().bind(value);
        label.setWrapText(true);
        return ScreenFeedback.track(label);
    }

    private static Button button(String title, Runnable action) {
        Button button = new Button(title);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static VBox panel(String title, Node... content) {
        VBox panel = new VBox(8, new Label(title));
        panel.getStyleClass().add("panel");
        panel.getChildren().addAll(content);
        return panel;
    }

    private final class Pager {
        final TableView<JsonNode> table;
        final Label status = new Label();
        final BooleanProperty busy = new SimpleBooleanProperty();
        final ComboBox<String> filter = new ComboBox<>();
        final Button previous;
        final Button next;
        final String route;
        int index;
        long total;

        Pager(String route, TableView<JsonNode> table, String... statuses) {
            this.route = route;
            this.table = table;
            filter.getItems().add("All statuses");
            filter.getItems().addAll(statuses);
            filter.getSelectionModel().selectFirst();
            filter.setOnAction(event -> { index = 0; load(); });
            filter.disableProperty().bind(busy);
            previous = button("Previous", () -> { index--; load(); });
            next = button("Next", () -> { index++; load(); });
            updateButtons();
        }

        VBox view() {
            Button refresh = button("Refresh", this::load);
            refresh.disableProperty().bind(busy);
            return panel("Records", new FlowPane(8, 8, filter, refresh, previous, next), status, table);
        }

        String selectedStatus() {
            var selected = table.getSelectionModel().getSelectedItem();
            return selected == null ? "" : selected.path("status").asText();
        }

        void load() {
            if (busy.get()) {
                return;
            }
            int page = index;
            String state = filter.getSelectionModel().getSelectedIndex() <= 0 ? "" : filter.getValue();
            busy.set(true);
            updateButtons();
            table.getItems().clear();
            status.setText("Loading…");
            call("REST", "List " + route, token -> gateways.maintenance.list(route, page, PAGE_SIZE, state, token),
                    result -> {
                        total = result.path("totalElements").asLong();
                        table.getItems().setAll(rows(result));
                        status.setText(total == 0 ? "No matching records" : "Page " + (index + 1) + " · " + total + " records");
                        updateButtons();
                    }, status, () -> { busy.set(false); updateButtons(); });
        }

        void perform(String operation, Call<JsonNode> action) {
            busy.set(true);
            updateButtons();
            status.setText("Submitting…");
            call("REST", operation, action, ignored -> load(), status,
                    () -> { busy.set(false); updateButtons(); });
        }

        void updateButtons() {
            previous.setDisable(busy.get() || index == 0);
            next.setDisable(busy.get() || (long) (index + 1) * PAGE_SIZE >= total);
        }
    }

    private static final class Plot {
        final LineChart<Number, Number> chart;
        final XYChart.Series<Number, Number> measured = new XYChart.Series<>();
        final XYChart.Series<Number, Number> warning = new XYChart.Series<>();
        final XYChart.Series<Number, Number> critical = new XYChart.Series<>();
        final int metric;

        Plot(int metric) {
            this.metric = metric;
            NumberAxis time = new NumberAxis();
            time.setLabel("Seconds from window start");
            NumberAxis value = new NumberAxis();
            value.setForceZeroInRange(false);
            chart = new LineChart<>(time, value);
            chart.setAnimated(false);
            chart.setCreateSymbols(false);
            chart.setLegendVisible(false);
            chart.setPrefHeight(210);
            chart.setMinWidth(0);
            chart.getStyleClass().add("trace");
            chart.getData().addAll(List.of(measured, warning, critical));
        }

        void clear() {
            measured.getData().clear();
            warning.getData().clear();
            critical.getData().clear();
        }

        void setData(ArrayDeque<Sample> history) {
            if (history.isEmpty()) {
                clear();
                return;
            }
            long origin = history.getFirst().time();
            measured.getData().setAll(history.stream()
                    .map(sample -> new XYChart.Data<Number, Number>((sample.time() - origin) / 1000.0,
                            sample.values()[metric])).toList());
            double span = Math.max(1, (history.getLast().time() - origin) / 1000.0);
            warning.getData().setAll(List.of(new XYChart.Data<>(0, WARNING[metric]),
                    new XYChart.Data<>(span, WARNING[metric])));
            critical.getData().setAll(List.of(new XYChart.Data<>(0, CRITICAL[metric]),
                    new XYChart.Data<>(span, CRITICAL[metric])));
        }
    }

    private static final class Pulse extends StackPane {
        private final Arc[] rings = new Arc[METRICS.length];
        private final Label label = new Label("No Live Data");

        Pulse() {
            Group graphics = new Group();
            for (int i = 0; i < rings.length; i++) {
                double radius = 76 - i * 12;
                Arc track = arc(radius, 359.9, "pulse-track");
                Arc ring = arc(radius, 0, "pulse-value");
                rings[i] = ring;
                graphics.getChildren().addAll(track, ring);
            }
            getChildren().addAll(graphics, label);
            setMinSize(174, 174);
            setPrefSize(174, 174);
        }

        void update(Sample sample) {
            String state = "RUNNING";
            for (int i = 0; i < rings.length; i++) {
                double value = sample == null ? 0 : sample.values()[i];
                String signal = value > CRITICAL[i] ? "critical" : value > WARNING[i] ? "warning" : "running";
                rings[i].setLength(-359.9 * Math.clamp(value / (CRITICAL[i] * 1.35), 0, 1));
                rings[i].getStyleClass().setAll("pulse-value", signal);
                if ("critical".equals(signal) || ("warning".equals(signal) && "RUNNING".equals(state))) {
                    state = signal.toUpperCase(Locale.ROOT);
                }
            }
            label.setText(sample == null ? "No Live Data" : state);
        }

        private static Arc arc(double radius, double length, String style) {
            Arc arc = new Arc(0, 0, radius, radius, 90, length);
            arc.setType(ArcType.OPEN);
            arc.getStyleClass().add(style);
            return arc;
        }
    }
}
