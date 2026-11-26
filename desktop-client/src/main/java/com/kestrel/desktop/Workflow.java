package com.kestrel.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class Workflow {
    private static final String BEARING = "00000000-0000-4000-8000-000000000101";
    private static final String[] METRICS = {"TEMPERATURE", "VIBRATION", "PRESSURE", "ENERGY"};
    private static final double[] LIMITS = {85, 7, 7, 15};
    private static final String RESERVED = "RESERVED";
    private static final String RELEASED = "RELEASED";

    private enum Step {
        ALERT, ORDER, CHECK, RESERVE, MARK_WAITING, WAITING, LINK, RELEASE,
        DONE, ALREADY_LINKED, STOPPED;

        boolean terminal() {
            return this == DONE || this == ALREADY_LINKED || this == STOPPED;
        }

        String display() {
            return switch (this) {
                case ALERT -> "REST · Create or recover alert";
                case ORDER -> "REST · Create or recover work order";
                case CHECK -> "SOAP · Check stock";
                case RESERVE -> "SOAP · Reserve or replay reservation";
                case MARK_WAITING -> "REST · Record stock shortage";
                case WAITING -> "WAITING_FOR_PARTS";
                case LINK -> "REST · Link confirmed reservation";
                case RELEASE -> "SOAP · Release unlinked reservation";
                case DONE -> "Reservation linked";
                case ALREADY_LINKED -> "Existing reservation found on work order";
                case STOPPED -> "Workflow stopped";
            };
        }
    }

    private record Arm(String machine, String part, int quantity, boolean pauseBeforeLink) {}
    private record Result(boolean resumable, String message) {}

    private final Gateways gateways;
    private final Executor worker;
    private final BiConsumer<String, String> events;
    private final Path directory = Path.of(System.getProperty("user.home"), ".kestrel");
    private final Path journal = directory.resolve("workflow.properties");
    private final SimpleBooleanProperty busy = new SimpleBooleanProperty();
    private final SimpleBooleanProperty pending = new SimpleBooleanProperty();
    private final SimpleBooleanProperty armed = new SimpleBooleanProperty();
    private final SimpleStringProperty state = new SimpleStringProperty("Disarmed");
    private volatile Screens.Access access;
    private Arm configuration;
    private int lastMetric = -1;
    private int consecutive;

    Workflow(Gateways gateways, Executor worker, BiConsumer<String, String> events) {
        this.gateways = gateways;
        this.worker = worker;
        this.events = events;
    }

    void begin(Screens.Access session) {
        end();
        access = session;
        run(null, true);
    }

    void end() {
        access = null;
        disarm();
        pending.set(false);
        busy.set(false);
        state.set("Disarmed");
    }

    Node controls(java.util.function.Supplier<String> machine, SimpleStringProperty role) {
        TextField part = new TextField(BEARING);
        part.setAccessibleText("Inventory part UUID");
        part.setPrefColumnCount(36);
        TextField quantity = new TextField("1");
        quantity.setAccessibleText("Requested quantity");
        quantity.setPrefColumnCount(4);
        CheckBox pause = new CheckBox("Pause after SOAP confirmation");
        Button arm = new Button("Arm selected machine");
        Button stop = new Button("Disarm");
        Button retry = new Button("Continue / retry saved step");
        Label progress = new Label();
        progress.textProperty().bind(state);
        progress.setWrapText(true);
        progress.getStyleClass().add("instrument");

        var allowed = javafx.beans.binding.Bindings.createBooleanBinding(
                () -> "ADMIN".equals(role.get()) || "MAINTENANCE_OPERATOR".equals(role.get()), role);
        arm.disableProperty().bind(allowed.not().or(busy).or(pending).or(armed));
        stop.disableProperty().bind(armed.not());
        retry.disableProperty().bind(allowed.not().or(busy).or(pending.not()));
        part.disableProperty().bind(busy.or(armed));
        quantity.disableProperty().bind(busy.or(armed));
        pause.disableProperty().bind(busy.or(armed));

        arm.setOnAction(event -> {
            try {
                int units = Integer.parseInt(quantity.getText().strip());
                if (units < 1) {
                    throw new IllegalArgumentException();
                }
                configuration = new Arm(machine.get(), id(part.getText().strip()), units, pause.isSelected());
                lastMetric = -1;
                consecutive = 0;
                armed.set(true);
                state.set("Armed for " + configuration.machine() + " · waiting for two critical readings");
            } catch (IllegalArgumentException invalid) {
                state.set("Enter a valid part UUID and a positive quantity.");
            }
        });
        stop.setOnAction(event -> {
            disarm();
            state.set("Disarmed");
        });
        retry.setOnAction(event -> run(null, false));

        FlowPane ribbon = new FlowPane(8, 8);
        ribbon.setAlignment(Pos.CENTER_LEFT);
        for (String item : new String[]{"gRPC · Detect", "REST · Alert", "REST · Work order",
                "SOAP · Stock check", "SOAP · Reserve", "REST · Link"}) {
            Label step = new Label(item);
            step.getStyleClass().add("panel");
            ribbon.getChildren().add(step);
        }

        FlowPane form = new FlowPane(8, 8, new Label("Part UUID"), part,
                new Label("Quantity"), quantity, pause);
        return new VBox(12, ribbon, form, new FlowPane(8, 8, arm, stop, retry), progress);
    }

    void sample(String machine, long time, double[] values) {
        if (!armed.get() || configuration == null || !configuration.machine().equals(machine)) {
            return;
        }
        long age = System.currentTimeMillis() - time;
        if (age < -4000 || age > 4000) {
            lastMetric = -1;
            consecutive = 0;
            return;
        }
        int metric = -1;
        for (int i = 0; i < LIMITS.length; i++) {
            if (Double.isFinite(values[i]) && values[i] > LIMITS[i]) {
                metric = i;
                break;
            }
        }
        if (metric < 0) {
            lastMetric = -1;
            consecutive = 0;
            return;
        }
        consecutive = metric == lastMetric ? consecutive + 1 : 1;
        lastMetric = metric;
        if (consecutive < 2) {
            return;
        }

        Properties job = new Properties();
        job.setProperty("machine", machine);
        job.setProperty("metric", METRICS[metric]);
        job.setProperty("value", Double.toString(values[metric]));
        job.setProperty("limit", Double.toString(LIMITS[metric]));
        job.setProperty("observed", Instant.ofEpochMilli(time).toString());
        job.setProperty("part", configuration.part());
        job.setProperty("quantity", Integer.toString(configuration.quantity()));
        job.setProperty("pause", Boolean.toString(configuration.pauseBeforeLink()));
        disarm();
        events.accept("gRPC", "Critical " + METRICS[metric] + " detected on " + machine);
        run(job, false);
    }

    private void disarm() {
        armed.set(false);
        configuration = null;
        lastMetric = -1;
        consecutive = 0;
    }

    private void run(Properties requested, boolean inspectOnly) {
        Screens.Access session = access;
        if (session == null || busy.get()) {
            return;
        }
        busy.set(true);
        try {
            worker.execute(() -> {
                Result result;
                try {
                    result = locked(session, requested, inspectOnly);
                } catch (Exception failure) {
                    String message = failure instanceof Gateways.Rejected rejected
                            ? rejected.getMessage()
                            : failure instanceof Gateways.InventoryFault fault
                            ? "Inventory fault: " + fault.code()
                            : "Workflow paused. Check the services and retry the saved step.";
                    result = new Result(Files.exists(journal), message);
                }
                Result completed = result;
                ui(session, () -> {
                    busy.set(false);
                    pending.set(completed.resumable());
                    state.set(completed.message());
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            busy.set(false);
        }
    }

    private Result locked(Screens.Access session, Properties requested, boolean inspectOnly) throws Exception {
        Files.createDirectories(directory);
        Path lockPath = directory.resolve("workflow.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) {
                return new Result(true, "Another desktop instance is using the workflow journal.");
            }
            Properties saved = load();
            Step previous = saved == null ? null : step(saved);

            if (inspectOnly || requested == null && (previous == null || previous.terminal())) {
                return previous == null ? new Result(false, "Disarmed")
                        : new Result(!previous.terminal(), previous.display()
                        + (previous.terminal() ? "" : " · Continue / retry to resume"));
            }
            if (requested != null && previous != null && !previous.terminal()) {
                return new Result(true, "An unfinished workflow already exists. Resume it first.");
            }

            Properties job = requested == null ? saved : requested;
            if (requested != null) {
                save(job, Step.ALERT);
            } else if (previous == Step.WAITING) {
                save(job, Step.CHECK);
            }
            return advance(session, job);
        }
    }

    private Result advance(Screens.Access session, Properties job) throws Exception {
        while (access == session) {
            Step current = step(job);
            if (current.terminal()) {
                return new Result(false, current.display());
            }
            ui(session, () -> state.set(current.display()));
            String token = session.token();
            String part = id(job.getProperty("part"));
            int quantity = Integer.parseInt(job.getProperty("quantity"));
            if (quantity < 1) {
                throw new IOException("Invalid saved quantity");
            }

            switch (current) {
                case ALERT -> createAlert(session, job, token);
                case ORDER -> createOrder(session, job, part, quantity, token);
                case CHECK -> checkStock(session, job, part, quantity, token);
                case RESERVE -> {
                    reserve(session, job, part, quantity, token);
                    if (step(job) == Step.LINK && Boolean.parseBoolean(job.getProperty("pause"))) {
                        return new Result(true, "SOAP confirmed " + job.getProperty("reservationId")
                                + " · Continue to link it through REST");
                    }
                }
                case MARK_WAITING -> markWaiting(session, job, token);
                case WAITING -> {
                    return new Result(true, "WAITING_FOR_PARTS · Retry when stock is available");
                }
                case LINK -> link(session, job, token);
                case RELEASE -> {
                    gateways.inventory.release(id(job.getProperty("reservationId")), token);
                    save(job, Step.STOPPED);
                    log(session, "SOAP", "Unlinked reservation released for cancelled order");
                }
                default -> throw new IllegalStateException("Unexpected workflow step");
            }
        }
        return new Result(true, "Workflow saved for the next sign-in");
    }

    private void createAlert(Screens.Access session, Properties job, String token) throws Exception {
        String machine = job.getProperty("machine");
        JsonNode registered = gateways.maintenance.list("machines", 0, 1, "", machine, token)
                .path("content").path(0);
        if (!machine.equals(registered.path("machineCode").asText())) {
            throw new Gateways.Rejected(404, "Register " + machine + " in Maintenance, then retry.");
        }
        String machineId = id(registered.path("id").asText());
        JsonNode alert = gateways.maintenance.create("alerts", Map.of(
                "machineId", machineId, "machineCode", machine,
                "metricType", job.getProperty("metric"), "severity", "CRITICAL",
                "observedValue", Double.parseDouble(job.getProperty("value")),
                "thresholdValue", Double.parseDouble(job.getProperty("limit")),
                "message", "Critical telemetry from " + machine, "occurredAt", job.getProperty("observed")), token);
        job.setProperty("machineId", machineId);
        job.setProperty("alertId", id(alert.path("id").asText()));
        save(job, Step.ORDER);
        log(session, "REST", "Alert confirmed · " + job.getProperty("alertId"));
    }

    private void createOrder(Screens.Access session, Properties job, String part, int units, String token)
            throws Exception {
        String machine = job.getProperty("machine");
        JsonNode order = gateways.maintenance.create("work-orders", Map.of(
                "machineId", id(job.getProperty("machineId")), "machineCode", machine,
                "alertId", id(job.getProperty("alertId")), "priority", "HIGH",
                "title", "Inspect " + machine + " · " + job.getProperty("metric"),
                "description", "Investigate the linked critical telemetry alert.",
                "partRequirement", Map.of("partId", part, "quantity", units)), token);
        String orderId = id(order.path("id").asText());
        String seed = "kestrel/v1/reserve/" + orderId + "/" + part + "/" + units;
        job.setProperty("orderId", orderId);
        job.setProperty("requestId", UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString());
        save(job, Step.CHECK);
        log(session, "REST", "Work order confirmed · " + orderId);
    }

    private void checkStock(Screens.Access session, Properties job, String part, int quantity, String token)
            throws Exception {
        JsonNode order = gateways.maintenance.get(orderPath(job), token);
        if (RESERVED.equals(order.path("reservationStatus").asText())) {
            save(job, Step.ALREADY_LINKED);
            log(session, "REST", "Work order already has a reservation");
            return;
        }
        if ("CANCELLED".equals(status(order))) {
            save(job, Step.STOPPED);
            return;
        }
        ensureChecking(job, order, token);
        int available;
        try {
            available = gateways.inventory.available(part, quantity, token);
        } catch (Exception failure) {
            pendingIfUncertain(session, job, token, failure);
            throw failure;
        }
        save(job, available < quantity ? Step.MARK_WAITING : Step.RESERVE);
        log(session, "SOAP", "Stock check confirmed · " + available + " available / " + quantity + " requested");
    }

    private void reserve(Screens.Access session, Properties job, String part, int quantity, String token)
            throws Exception {
        Gateways.Reservation result;
        try {
            result = gateways.inventory.reserve(part, quantity, id(job.getProperty("orderId")),
                    id(job.getProperty("requestId")), token);
        } catch (Gateways.InventoryFault failure) {
            if ("INSUFFICIENT_STOCK".equals(failure.code())) {
                save(job, Step.MARK_WAITING);
                log(session, "SOAP", "Reservation declined · insufficient stock");
                return;
            }
            pendingIfUncertain(session, job, token, failure);
            throw failure;
        } catch (Exception failure) {
            pendingIfUncertain(session, job, token, failure);
            throw failure;
        }

        if (RELEASED.equals(result.status())) {
            save(job, Step.STOPPED);
            log(session, "SOAP", "The saved request refers to a previously released reservation");
            return;
        }
        if (!RESERVED.equals(result.status())) {
            throw new IOException("Unexpected reservation status");
        }
        job.setProperty("reservationId", id(result.id()));
        save(job, Step.LINK);
        log(session, "SOAP", "Reservation confirmed · " + result.id());
    }

    private void markWaiting(Screens.Access session, Properties job, String token) throws Exception {
        JsonNode order = gateways.maintenance.get(orderPath(job), token);
        if ("CANCELLED".equals(status(order))) {
            save(job, Step.STOPPED);
            return;
        }
        if (!"WAITING_FOR_PARTS".equals(status(order))) {
            ensureChecking(job, order, token);
            setStatus(job, "WAITING_FOR_PARTS", token);
        }
        save(job, Step.WAITING);
        log(session, "REST", "Work order saved as WAITING_FOR_PARTS");
    }

    private void link(Screens.Access session, Properties job, String token) throws Exception {
        String reservation = id(job.getProperty("reservationId"));
        JsonNode order = gateways.maintenance.get(orderPath(job), token);
        if (reservation.equals(order.path("reservationId").asText())) {
            if (RESERVED.equals(order.path("reservationStatus").asText())) {
                save(job, Step.DONE);
                log(session, "REST", "The confirmed reservation is already linked");
            } else {
                save(job, Step.STOPPED);
                log(session, "REST", "The linked reservation has already been released");
            }
            return;
        }
        if ("CANCELLED".equals(status(order))) {
            save(job, Step.RELEASE);
            return;
        }
        JsonNode linked = gateways.maintenance.patch(orderPath(job) + "/reservation",
                Map.of("reservationId", reservation, "status", RESERVED), token);
        if (!reservation.equals(linked.path("reservationId").asText())
                || !RESERVED.equals(linked.path("reservationStatus").asText())) {
            throw new IOException("REST link not confirmed");
        }
        save(job, Step.DONE);
        log(session, "REST", "Reservation linked · " + reservation);
    }

    private void pendingIfUncertain(Screens.Access session, Properties job, String token, Exception cause) {
        if (cause instanceof Gateways.Rejected
                || cause instanceof Gateways.InventoryFault fault && !"SERVICE_UNAVAILABLE".equals(fault.code())) {
            return;
        }
        try {
            if ("CHECKING_INVENTORY".equals(status(gateways.maintenance.get(orderPath(job), token)))) {
                setStatus(job, "PENDING_RESERVATION", token);
                log(session, "REST", "PENDING_RESERVATION · Inventory response unavailable");
            }
        } catch (Exception unavailable) {
            log(session, "REST", "Pending status could not be confirmed; retry step is saved locally");
        }
    }

    private void ensureChecking(Properties job, JsonNode order, String token) throws Exception {
        switch (status(order)) {
            case "CHECKING_INVENTORY" -> { }
            case "OPEN", "WAITING_FOR_PARTS", "PENDING_RESERVATION" ->
                    setStatus(job, "CHECKING_INVENTORY", token);
            default -> throw new Gateways.Rejected(409, "This work order is no longer eligible for inventory checks.");
        }
    }

    private void setStatus(Properties job, String status, String token) throws Exception {
        JsonNode result = gateways.maintenance.patch(orderPath(job) + "/status", Map.of("status", status), token);
        if (!status.equals(status(result))) {
            throw new IOException("Work-order status was not confirmed");
        }
    }

    private static String status(JsonNode order) {
        return order.path("status").asText();
    }

    private static String orderPath(Properties job) {
        return "work-orders/" + id(job.getProperty("orderId"));
    }

    private static String id(String value) {
        return UUID.fromString(value).toString();
    }

    private static Step step(Properties job) {
        return Step.valueOf(job.getProperty("step"));
    }

    private Properties load() throws IOException {
        if (!Files.exists(journal)) {
            return null;
        }
        Properties result = new Properties();
        try (var input = Files.newInputStream(journal)) {
            result.load(input);
        }
        return result;
    }

    private void save(Properties job, Step next) throws IOException {
        Path temporary = Files.createTempFile(directory, "workflow-", ".tmp");
        job.setProperty("step", next.name());
        try {
            try (var output = Files.newOutputStream(temporary)) {
                job.store(output, "Kestrel orchestration state");
            }
            Files.move(temporary, journal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void log(Screens.Access session, String protocol, String message) {
        ui(session, () -> events.accept(protocol, message));
    }

    private void ui(Screens.Access session, Runnable update) {
        Platform.runLater(() -> {
            if (access == session) {
                update.run();
            }
        });
    }
}
