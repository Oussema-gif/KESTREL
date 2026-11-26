package com.kestrel.maintenance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ApiModels {
    static final String MACHINE_CODE = "[A-Z0-9]+(?:-[A-Z0-9]+)*";
    static final String UTC_TIMESTAMP = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z";

    private ApiModels() {
    }

    enum Role { ADMIN, MAINTENANCE_OPERATOR, TECHNICIAN, INVENTORY_OPERATOR }
    enum MachineStatus { RUNNING, WARNING, CRITICAL, OFFLINE, MAINTENANCE }
    enum MetricType { TEMPERATURE, VIBRATION, PRESSURE, ENERGY }
    enum AlertSeverity { WARNING, CRITICAL }
    enum AlertStatus { ACTIVE, ACKNOWLEDGED, RESOLVED }
    enum Priority { LOW, MEDIUM, HIGH, URGENT }
    enum ReservationStatus { RESERVED, RELEASED }
    enum WorkStatus {
        OPEN, CHECKING_INVENTORY, READY_FOR_MAINTENANCE, WAITING_FOR_PARTS,
        PENDING_RESERVATION, IN_PROGRESS, COMPLETED, CANCELLED;

        boolean terminal() {
            return this == COMPLETED || this == CANCELLED;
        }
    }

    record LoginRequest(@NotBlank @Size(max = 100) String username,
                        @NotNull @Size(min = 1, max = 256) String password) {}
    record RefreshRequest(@NotNull @Size(min = 1, max = 1024) String refreshToken) {}
    record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn,
                  long refreshExpiresIn) {}
    record UserProfile(UUID id, String username, String fullName, Role role) {}

    record MachineCreate(@NotNull @Size(max = 32) @Pattern(regexp = MACHINE_CODE) String machineCode,
                         @NotBlank @Size(max = 120) String name,
                         @NotBlank @Size(max = 120) String location,
                         @NotBlank @Size(max = 120) String model) {}
    record Machine(UUID id, String machineCode, String name, String location, String model,
                   MachineStatus status, Instant createdAt, Instant updatedAt) {}
    record MachineObservation(UUID machineId, String machineCode, MachineStatus status, Instant observedAt) {}

    record AlertCreate(@NotNull UUID machineId,
                       @NotNull @Size(max = 32) @Pattern(regexp = MACHINE_CODE) String machineCode,
                       @NotNull MetricType metricType, @NotNull AlertSeverity severity,
                       @NotNull Double observedValue, @NotNull Double thresholdValue,
                       @NotBlank @Size(max = 500) String message,
                       @NotNull @Pattern(regexp = UTC_TIMESTAMP) String occurredAt) {}
    record Alert(UUID id, UUID machineId, String machineCode, MetricType metricType, AlertSeverity severity,
                 double observedValue, double thresholdValue, String message, Instant occurredAt,
                 AlertStatus status, Instant acknowledgedAt, Instant resolvedAt) {}

    record PartRequirement(@NotNull UUID partId, @NotNull @Positive Integer quantity) {}
    record WorkOrderCreate(@NotNull UUID machineId,
                           @NotNull @Size(max = 32) @Pattern(regexp = MACHINE_CODE) String machineCode,
                           UUID alertId, @NotBlank @Size(max = 160) String title,
                           @NotBlank @Size(max = 2000) String description,
                           @NotNull Priority priority, @Valid PartRequirement partRequirement) {}
    record WorkOrder(UUID id, UUID machineId, String machineCode, UUID alertId, String title,
                     String description, Priority priority, PartRequirement partRequirement,
                     WorkStatus status, UUID technicianId, UUID reservationId, ReservationStatus reservationStatus,
                     Instant createdAt, Instant updatedAt) {}
    record StatusChange(@NotNull WorkStatus status) {}
    record TechnicianAssignment(@NotNull UUID technicianId) {}
    record ReservationUpdate(@NotNull UUID reservationId, @NotNull ReservationStatus status) {}
    record Technician(UUID id, String fullName, String email, boolean active) {}
    record AuditEvent(UUID id, UUID actorId, String action, String target, String result,
                      Instant timestamp, String source) {}

    record Page<T>(List<T> content, int page, int size, long totalElements) {}
    record Creation<T>(T value, boolean created) {}
}
