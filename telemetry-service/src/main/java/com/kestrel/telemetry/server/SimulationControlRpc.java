package com.kestrel.telemetry.server;

import com.kestrel.telemetry.SetSimulationScenarioRequest;
import com.kestrel.telemetry.SetSimulationScenarioResponse;
import com.kestrel.telemetry.SimulationControlServiceGrpc;
import com.kestrel.telemetry.SimulationScenario;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

final class SimulationControlRpc extends SimulationControlServiceGrpc.SimulationControlServiceImplBase {
    private final TelemetrySimulator simulator;

    SimulationControlRpc(TelemetrySimulator simulator) {
        this.simulator = simulator;
    }

    @Override
    public void setSimulationScenario(SetSimulationScenarioRequest request, StreamObserver<SetSimulationScenarioResponse> response) {
        SimulationScenario scenario = request.getScenario();
        if (scenario == SimulationScenario.SCENARIO_UNSPECIFIED || scenario == SimulationScenario.UNRECOGNIZED) {
            response.onError(Status.INVALID_ARGUMENT.withDescription("Select a supported simulation scenario.").asRuntimeException());
            return;
        }
        String code = request.getMachineCode();
        try {
            simulator.changeScenario(code, scenario, () -> {
                response.onNext(SetSimulationScenarioResponse.newBuilder().setMachineCode(code).setScenario(scenario).build());
                response.onCompleted();
            }, (ServerCallStreamObserver<?>) response);
        } catch (StatusRuntimeException rejected) {
            response.onError(rejected);
        }
    }
}
