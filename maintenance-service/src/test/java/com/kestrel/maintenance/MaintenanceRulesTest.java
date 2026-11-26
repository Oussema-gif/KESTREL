package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaintenanceRulesTest {
    @Test
    void gatesReadinessAndStartOnReportedReservationAndAssignment() {
        var open = order(WorkStatus.OPEN, true, false, null);
        assertThatCode(() -> WorkOrderRules.requireStatusChange(open, WorkStatus.CHECKING_INVENTORY))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> WorkOrderRules.requireStatusChange(open, WorkStatus.READY_FOR_MAINTENANCE))
                .isInstanceOf(ApiProblem.class);

        var ready = order(WorkStatus.READY_FOR_MAINTENANCE, true, true, ReservationStatus.RESERVED);
        assertThatCode(() -> WorkOrderRules.requireStatusChange(ready, WorkStatus.IN_PROGRESS)).doesNotThrowAnyException();
        assertThatThrownBy(() -> WorkOrderRules.requireStatusChange(ready, WorkStatus.CANCELLED))
                .isInstanceOf(ApiProblem.class);
        var released = order(WorkStatus.READY_FOR_MAINTENANCE, true, true, ReservationStatus.RELEASED);
        assertThatThrownBy(() -> WorkOrderRules.requireStatusChange(released, WorkStatus.IN_PROGRESS))
                .isInstanceOf(ApiProblem.class);
    }

    @Test
    void boundsRequestsBySourceAndFailuresByAccount() {
        var throttle = new LoginThrottle(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < 30; i++) {
            throttle.checkSource("127.0.0.1");
        }
        assertThatThrownBy(() -> throttle.checkSource("127.0.0.1")).isInstanceOf(ApiProblem.class);
        assertThatCode(() -> throttle.checkSource("127.0.0.2")).doesNotThrowAnyException();

        for (int i = 0; i < 5; i++) {
            throttle.checkAccount("user");
            throttle.failed("user");
        }
        assertThatThrownBy(() -> throttle.checkAccount("user")).isInstanceOf(ApiProblem.class);
        assertThatCode(() -> throttle.checkAccount("other")).doesNotThrowAnyException();
    }

    private static WorkOrder order(WorkStatus status, boolean part, boolean assigned, ReservationStatus reservation) {
        return new WorkOrder(UUID.randomUUID(), UUID.randomUUID(), "MACHINE-1", null, "Order", "Description",
                Priority.MEDIUM, part ? new PartRequirement(UUID.randomUUID(), 1) : null, status,
                assigned ? UUID.randomUUID() : null, reservation == null ? null : UUID.randomUUID(), reservation,
                Instant.EPOCH, Instant.EPOCH);
    }
}
