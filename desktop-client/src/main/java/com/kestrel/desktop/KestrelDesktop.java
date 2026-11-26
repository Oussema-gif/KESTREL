package com.kestrel.desktop;

import com.kestrel.telemetry.TelemetryReading;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class KestrelDesktop extends Application {
    private enum Protocol {
        REST("REST :8081"), SOAP("SOAP :8082"), GRPC("gRPC :9090");
        private final String caption;
        Protocol(String caption) { this.caption = caption; }
    }

    private final ScheduledThreadPoolExecutor workers =
            new ScheduledThreadPoolExecutor(4, Thread.ofPlatform().daemon().name("kestrel-desktop-", 0).factory());
    private final EnumMap<Protocol, Label> indicators = new EnumMap<>(Protocol.class);
    private final BorderPane shell = new BorderPane();
    private final HBox navigation = new HBox(5);
    private final HBox machineControls = new HBox(8);
    private final ToggleGroup routes = new ToggleGroup();
    private final StringProperty user = new SimpleStringProperty("Signed out");
    private final StringProperty stock = new SimpleStringProperty("Waiting for Inventory");
    private final StringProperty readings = new SimpleStringProperty("Waiting for Telemetry");
    private final StringProperty freshness = new SimpleStringProperty("No live reading");
    private Gateways services;
    private Screens screens;
    private Timeline staleCheck;
    private volatile WatchSet current;
    private Session session;
    private String machine = "MACHINE-001";
    private final TextField machineInput = new TextField(machine);
    private final StringProperty sessionTime = new SimpleStringProperty("");
    private long shownTimestamp = Long.MIN_VALUE;
    private long lastReadingNanos;
    private boolean offline;

    @Override
    public void start(Stage stage) {
        workers.setRemoveOnCancelPolicy(true);
        services = new Gateways();
        screens = new Screens(services, workers, this::selectMachine, () -> machine, readings, freshness, stock);
        shell.getStyleClass().add("forge-shell");
        shell.setTop(ForgeTopBar.build(header(), navigation, screens.motionPreference()));
        shell.setBottom(ForgeSignals.style(protocolRail()));
        navigation.getStyleClass().add("navigation");
        navigation.setVisible(false);
        navigation.setManaged(false);
        showLogin();

        staleCheck = new Timeline(new KeyFrame(Duration.millis(500), event -> {
            updateFreshness();
            sessionTime.set(session == null ? "" : Math.max(0,
                    java.time.Duration.between(Instant.now(), session.tokens.expiresAt()).toMinutes()) + " min");
        }));
        staleCheck.setCycleCount(Timeline.INDEFINITE);
        staleCheck.play();

        Scene scene = new Scene(shell, 1120, 700);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/kestrel/desktop/theme.css")).toExternalForm());
        stage.setMinWidth(860);
        stage.setMinHeight(540);
        stage.setTitle("Kestrel · Forge Ops");
        scene.getStylesheets().add(KestrelDesktop.class.getResource("forge-ops.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private HBox header() {
        Label brand = new Label("KESTREL");
        brand.getStyleClass().add("brand");
        machineInput.setAccessibleText("Machine code");
        machineInput.setPrefColumnCount(12);
        Button select = new Button("Switch machine");
        select.setOnAction(event -> {
            String value = machineInput.getText().strip().toUpperCase(Locale.ROOT);
            if (!value.matches("[A-Z0-9]+(?:-[A-Z0-9]+)*") || value.length() > 32) {
                machineInput.setPromptText("Enter a valid machine code");
                machineInput.clear();
                return;
            }
            selectMachine(value);
        });
        machineControls.getChildren().addAll(machineInput, select);
        machineControls.setAlignment(Pos.CENTER_LEFT);
        machineControls.setVisible(false);
        machineControls.setManaged(false);

        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        Button logout = new Button("Sign out");
        logout.visibleProperty().bind(machineControls.visibleProperty());
        logout.managedProperty().bind(logout.visibleProperty());
        logout.setOnAction(event -> signOut());

        HBox result = new HBox(18, brand, machineControls, space, bound(user), bound(sessionTime), logout);
        result.setAlignment(Pos.CENTER_LEFT);
        result.getStyleClass().add("header");
        return result;
    }

    private FlowPane protocolRail() {
        FlowPane rail = new FlowPane(26, 8);
        rail.getStyleClass().add("protocol-rail");
        for (Protocol protocol : Protocol.values()) {
            Label name = new Label(protocol.caption);
            name.getStyleClass().add(protocol.name().toLowerCase(Locale.ROOT));
            Label status = new Label("Sign-in required");
            indicators.put(protocol, status);
            rail.getChildren().add(new HBox(8, name, status));
        }
        return rail;
    }

    private void showLogin() {
        TextField username = new TextField("admin");
        username.setAccessibleText("Username");
        PasswordField password = new PasswordField();
        password.setAccessibleText("Password");
        Button login = new Button("Sign in");
        login.setDefaultButton(true);
        Label result = new Label();
        result.setWrapText(true);
        login.setOnAction(event -> {
            String name = username.getText().strip();
            String secret = password.getText();
            password.clear();
            if (name.isBlank() || secret.isEmpty()) {
                result.setText("Enter your Maintenance username and password.");
                return;
            }

            login.setDisable(true);
            result.setText("Signing in through Maintenance…");
            later(0, () -> {
                try {
                    var tokens = services.maintenance.login(name, secret);
                    Platform.runLater(() -> {
                        session = new Session(tokens);
                        screens.begin(session::access);
                        user.set(name);
                        machineControls.setVisible(true);
                        machineControls.setManaged(true);
                        showNavigation();
                        startWatching();
                    });
                } catch (Gateways.Rejected rejected) {
                    Platform.runLater(() -> showLoginFailure(login, result, rejected.getMessage()));
                } catch (Exception failure) {
                    Platform.runLater(() -> showLoginFailure(login, result, "Maintenance is unavailable. Try again."));
                }
            });
        });

        VBox form = new VBox(12, heading("FORGE OPS"), new Label("Maintenance account"),
                new Label("Username"), username, new Label("Password"), password, login, result);
        form.setMaxWidth(460);
        form.getStyleClass().add("panel");
        VBox page = new VBox(form);
        page.setAlignment(Pos.CENTER);
        page.setPadding(new Insets(32));
        shell.setCenter(page);
        Platform.runLater(password::requestFocus);
    }

    private static void showLoginFailure(Button login, Label result, String message) {
        result.setText(message);
        login.setDisable(false);
    }

    private void showNavigation() {
        navigation.getChildren().clear();
        navigation.setVisible(true);
        navigation.setManaged(true);
        for (String name : new String[]{"Command Deck", "Live Telemetry", "Alerts", "Work Orders",
                "Inventory", "Orchestration", "Settings"}) {
            ToggleButton button = new ToggleButton(name);
            button.setToggleGroup(routes);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(event -> {
                button.setSelected(true);
                showPage(name);
            });
            navigation.getChildren().add(button);
            if ("Command Deck".equals(name)) {
                button.setSelected(true);
            }
        }
        showPage("Command Deck");
    }

    private void showPage(String route) {
        shell.setCenter(screens.page(route));
    }

    private void selectMachine(String code) {
        machine = code;
        machineInput.setText(code);
        startWatching();
    }

    private void showProtocol(Protocol protocol, String status) {
        indicators.get(protocol).setText(status);
        screens.protocol(protocol.caption, status);
    }

    private void signOut() {
        stopWatching();
        Session old = session;
        session = null;
        sessionTime.set("");
        screens.end();
        if (old != null) {
            later(0, () -> {
                try {
                    old.revoke();
                } catch (Exception unavailable) {
                    // Local sign-out is already complete.
                }
            });
        }
        user.set("Signed out");
        machineControls.setVisible(false);
        machineControls.setManaged(false);
        navigation.setVisible(false);
        navigation.setManaged(false);
        indicators.values().forEach(label -> label.setText("Sign-in required"));
        showLogin();
    }

    private void startWatching() {
        stopWatching();
        WatchSet watch = new WatchSet(session, machine);
        current = watch;
        shownTimestamp = Long.MIN_VALUE;
        screens.machineChanged();
        lastReadingNanos = 0;
        offline = false;
        freshness.set("Waiting for data");
        readings.set("Waiting for " + machine);
        stock.set("Waiting for Inventory");
        indicators.values().forEach(label -> label.setText("Checking"));
        later(0, () -> probe(watch, Protocol.REST, 0));
        later(0, () -> probe(watch, Protocol.SOAP, 0));
        later(0, () -> stream(watch, 0));
    }

    private void stopWatching() {
        WatchSet old = current;
        current = null;
        if (old != null) {
            old.context.close();
        }
    }

    private void probe(WatchSet watch, Protocol protocol, int failures) {
        if (current != watch) {
            return;
        }
        long start = System.nanoTime();
        try {
            String token = watch.session.access();
            if (protocol == Protocol.REST) {
                var profile = services.maintenance.profile(token);
                ui(watch, () -> {
                    user.set(profile.fullName() + " · " + profile.role());
                    screens.role(profile.role());
                });
            } else {
                int units = services.inventory.checkBearing(token);
                ui(watch, () -> stock.set(units + " available · BRG-6205"));
            }
            connected(watch, protocol, start);
            later(4, () -> probe(watch, protocol, 0));
        } catch (Exception failure) {
            if (protocol == Protocol.SOAP) {
                ui(watch, () -> stock.set("Stock unavailable · awaiting a confirmed SOAP response"));
            }
            retry(watch, protocol, failures, failure, next -> probe(watch, protocol, next));
        }
    }

    private void stream(WatchSet watch, int failures) {
        if (current != watch) {
            return;
        }
        long start = System.nanoTime();
        try {
            String token = watch.session.access();
            try {
                var snapshot = services.telemetry.snapshot(watch.machine, token);
                ui(watch, () -> sample(snapshot.getMachineCode(), snapshot.getTimestampEpochMs(),
                        snapshot.getTemperatureC(), snapshot.getVibrationMmS(), snapshot.getPressureBar(),
                        snapshot.getEnergyKw(), "OFFLINE".equals(snapshot.getMachineStatus())));
            } catch (StatusRuntimeException missing) {
                if (missing.getStatus().getCode() != Status.Code.FAILED_PRECONDITION) {
                    throw missing;
                }
                ui(watch, () -> freshness.set("Machine has no initial reading"));
            }
            connected(watch, Protocol.GRPC, start);
            // Keep just the newest unrendered sample if the JavaFX thread is busy.
            var pending = new AtomicReference<TelemetryReading>();
            var version = new AtomicLong();
            watch.context.run(() -> services.telemetry.watch(watch.machine, token, new StreamObserver<>() {
                @Override
                public void onNext(TelemetryReading data) {
                    pending.set(data);
                    if (version.getAndIncrement() == 0) {
                        ui(watch, () -> {
                            version.set(0);
                            TelemetryReading latest = pending.getAndSet(null);
                            if (latest != null) {
                                sample(latest.getMachineCode(), latest.getTimestampEpochMs(),
                                        latest.getTemperatureC(), latest.getVibrationMmS(),
                                        latest.getPressureBar(), latest.getEnergyKw(), false);
                            }
                        });
                    }
                }

                @Override
                public void onError(Throwable failure) {
                    retry(watch, Protocol.GRPC, 0, failure, next -> stream(watch, next));
                }

                @Override
                public void onCompleted() {
                    onError(Status.UNAVAILABLE.asRuntimeException());
                }
            }));
        } catch (Exception failure) {
            retry(watch, Protocol.GRPC, failures, failure, next -> stream(watch, next));
        }
    }

    private void retry(WatchSet watch, Protocol protocol, int failures, Throwable failure,
                       java.util.function.IntConsumer resume) {
        if (current != watch) {
            return;
        }
        int http = failure instanceof Gateways.Rejected rejected ? rejected.status() : 0;
        Status.Code grpc = Status.fromThrowable(failure).getCode();
        if (http == 401 || grpc == Status.Code.UNAUTHENTICATED) {
            ui(watch, () -> showProtocol(protocol, "Sign-in required"));
            return;
        }
        if (http == 403 || grpc == Status.Code.PERMISSION_DENIED) {
            ui(watch, () -> showProtocol(protocol, "Access denied"));
            return;
        }
        if (http == 404 || grpc == Status.Code.NOT_FOUND || grpc == Status.Code.INVALID_ARGUMENT) {
            ui(watch, () -> showProtocol(protocol, "Resource unavailable"));
            return;
        }

        long delay = 1L << Math.min(failures, 4);
        ui(watch, () -> showProtocol(protocol, "Reconnecting · retry in " + delay + " s"));
        later(delay, () -> resume.accept(Math.min(failures + 1, 4)));
    }

    private void connected(WatchSet watch, Protocol protocol, long start) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        ui(watch, () -> showProtocol(protocol, "Connected · " + elapsed + " ms"));
    }

    private void sample(String code, long timestamp, double temperature, double vibration,
                        double pressure, double power, boolean machineOffline) {
        if (timestamp < shownTimestamp) {
            return;
        }
        shownTimestamp = timestamp;
        offline = machineOffline;
        lastReadingNanos = System.nanoTime();
        readings.set(String.format(Locale.ROOT, "%s%n%.1f °C   %.1f mm/s   %.1f bar   %.1f kW%n%s",
                code, temperature, vibration, pressure, power, Instant.ofEpochMilli(timestamp)));
        screens.sample(timestamp, temperature, vibration, pressure, power, machineOffline);
        updateFreshness();
    }

    private void updateFreshness() {
        if (lastReadingNanos == 0) {
            return;
        }
        boolean stale = offline || System.currentTimeMillis() - shownTimestamp > 4000
                || System.nanoTime() - lastReadingNanos > TimeUnit.SECONDS.toNanos(4);
        freshness.set(offline ? "Machine offline · stale" : stale ? "Stale · awaiting new readings" : "Live");
    }

    private void ui(WatchSet watch, Runnable change) {
        Platform.runLater(() -> {
            if (current == watch && !workers.isShutdown()) {
                change.run();
            }
        });
    }

    private void later(long seconds, Runnable task) {
        try {
            workers.schedule(task, seconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException closing) {
            // The desktop is closing.
        }
    }

    private static Label heading(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("heading");
        return label;
    }

    private static Label bound(StringProperty text) {
        Label label = new Label();
        label.textProperty().bind(text);
        label.setWrapText(true);
        return label;
    }

    @Override
    public void stop() {
        staleCheck.stop();
        screens.end();
        stopWatching();
        workers.shutdownNow();
        services.close();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private final class Session {
        private volatile Gateways.Tokens tokens;

        Session(Gateways.Tokens tokens) {
            this.tokens = tokens;
        }

        synchronized String access() throws Exception {
            if (!Instant.now().plusSeconds(30).isBefore(tokens.expiresAt())) {
                tokens = services.maintenance.refresh(tokens.refresh());
            }
            return tokens.access();
        }

        synchronized void revoke() throws Exception {
            services.maintenance.logout(tokens.refresh());
        }
    }

    private final class WatchSet {
        final Session session;
        final String machine;
        final Context.CancellableContext context = Context.ROOT.withCancellation();

        WatchSet(Session session, String machine) {
            this.session = session;
            this.machine = machine;
        }
    }
}
