package com.kestrel.telemetry.server;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TelemetryServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TelemetryServer.class);
    private final Server server;
    private final TelemetrySimulator simulator;
    private final AtomicBoolean stopped = new AtomicBoolean();

    TelemetryServer(TelemetrySettings settings, TelemetrySimulator simulator, TelemetryAuth auth) throws IOException {
        this.simulator = simulator;
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", settings.port()))
                .maxInboundMessageSize(16_384)
                .intercept(auth)
                .addService(new TelemetryRpc(simulator))
                .addService(new SimulationControlRpc(simulator))
                .build().start();
        LOG.info("Telemetry gRPC listening on 127.0.0.1:{}", port());
    }

    int port() {
        return server.getPort();
    }

    void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        server.shutdown();
        try {
            simulator.disconnect().get(2, TimeUnit.SECONDS);
            if (server.awaitTermination(3, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException notDrained) {
            LOG.warn("Telemetry streams did not finish during graceful shutdown.");
        } finally {
            if (!server.isTerminated()) {
                server.shutdownNow();
                try {
                    server.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
