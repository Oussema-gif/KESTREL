package com.kestrel.telemetry.server;

import com.kestrel.telemetry.MachineRequest;
import com.kestrel.telemetry.TelemetryReading;
import com.kestrel.telemetry.TelemetryServiceGrpc;
import com.kestrel.telemetry.TelemetrySnapshot;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

final class TelemetryRpc extends TelemetryServiceGrpc.TelemetryServiceImplBase {
    private final TelemetrySimulator simulator;

    TelemetryRpc(TelemetrySimulator simulator) {
        this.simulator = simulator;
    }

    @Override
    public void getMachineSnapshot(MachineRequest request, StreamObserver<TelemetrySnapshot> response) {
        try {
            response.onNext(simulator.snapshot(request.getMachineCode()).snapshot());
            response.onCompleted();
        } catch (StatusRuntimeException rejected) {
            response.onError(rejected);
        }
    }

    @Override
    public void streamMachineTelemetry(MachineRequest request, StreamObserver<TelemetryReading> response) {
        var stream = (ServerCallStreamObserver<TelemetryReading>) response;
        try {
            simulator.subscribe(request.getMachineCode(), stream, TelemetryAuth.ACCESS_EXPIRY.get());
        } catch (StatusRuntimeException rejected) {
            stream.onError(rejected);
        }
    }
}
