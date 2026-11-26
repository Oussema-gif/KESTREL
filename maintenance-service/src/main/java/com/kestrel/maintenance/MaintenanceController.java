package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;
import static com.kestrel.maintenance.RequestMappingConfig.uuid;
import static com.kestrel.maintenance.SecurityConfig.MAINTENANCE_READ;
import static com.kestrel.maintenance.SecurityConfig.OPERATORS;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class MaintenanceController {
    private final MachineAlerts machineAlerts;
    private final WorkOrders orders;
    private final AuditLog audit;

    MaintenanceController(MachineAlerts machineAlerts, WorkOrders orders, AuditLog audit) {
        this.machineAlerts = machineAlerts;
        this.orders = orders;
        this.audit = audit;
    }

    @GetMapping("/machines")
    Page<Machine> machines(@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
                           @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
                           @RequestParam(name = "machineCode", required = false) @Size(max = 32) @Pattern(regexp = MACHINE_CODE)
                           String machineCode, @RequestParam(name = "status", required = false) MachineStatus status) {
        return machineAlerts.machines(page, size, machineCode, status);
    }

    @PostMapping("/machines")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Machine> createMachine(@Valid @RequestBody MachineCreate request,
                                         @AuthenticationPrincipal Jwt jwt, HttpServletRequest servlet) {
        Machine machine = machineAlerts.createMachine(request, Actor.from(jwt, servlet));
        return ResponseEntity.created(URI.create("/api/v1/machines/" + machine.id())).body(machine);
    }

    @GetMapping("/machines/{machineId}")
    Machine getMachine(@PathVariable("machineId") String machineId) {
        return machineAlerts.machine(uuid(machineId));
    }

    @GetMapping("/machines/{machineId}/status")
    MachineObservation status(@PathVariable("machineId") String machineId) {
        return machineAlerts.observation(uuid(machineId));
    }

    @GetMapping("/alerts")
    @PreAuthorize(MAINTENANCE_READ)
    Page<Alert> alerts(@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
                       @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
                       @RequestParam(name = "machineId", required = false) String machineId,
                       @RequestParam(name = "machineCode", required = false) @Size(max = 32) @Pattern(regexp = MACHINE_CODE)
                       String machineCode, @RequestParam(name = "status", required = false) AlertStatus status,
                       @RequestParam(name = "severity", required = false) AlertSeverity severity) {
        return machineAlerts.alerts(page, size, uuid(machineId), machineCode, status, severity);
    }

    @PostMapping("/alerts")
    @PreAuthorize(OPERATORS)
    ResponseEntity<Alert> createAlert(@Valid @RequestBody AlertCreate request) {
        Creation<Alert> result = machineAlerts.createAlert(request);
        return respond(result, "/api/v1/alerts/" + result.value().id());
    }

    @GetMapping("/alerts/{alertId}")
    @PreAuthorize(MAINTENANCE_READ)
    Alert alert(@PathVariable("alertId") String alertId) {
        return machineAlerts.alert(uuid(alertId));
    }

    @PatchMapping("/alerts/{alertId}/acknowledge")
    @PreAuthorize(OPERATORS)
    Alert acknowledge(@PathVariable("alertId") String alertId) {
        return machineAlerts.acknowledge(uuid(alertId));
    }

    @PatchMapping("/alerts/{alertId}/resolve")
    @PreAuthorize(OPERATORS)
    Alert resolve(@PathVariable("alertId") String alertId) {
        return machineAlerts.resolve(uuid(alertId));
    }

    @GetMapping("/work-orders")
    Page<WorkOrder> workOrders(@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
                               @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
                               @RequestParam(name = "machineId", required = false) String machineId,
                               @RequestParam(name = "machineCode", required = false) @Size(max = 32) @Pattern(regexp = MACHINE_CODE)
                               String machineCode, @RequestParam(name = "status", required = false) WorkStatus status,
                               @RequestParam(name = "technicianId", required = false) String technicianId,
                               @AuthenticationPrincipal Jwt jwt, HttpServletRequest servlet) {
        return orders.list(page, size, uuid(machineId), machineCode, status, uuid(technicianId), Actor.from(jwt, servlet));
    }

    @PostMapping("/work-orders")
    @PreAuthorize(OPERATORS)
    ResponseEntity<WorkOrder> createOrder(@Valid @RequestBody WorkOrderCreate request,
                                          @AuthenticationPrincipal Jwt jwt, HttpServletRequest servlet) {
        Creation<WorkOrder> result = orders.create(request, Actor.from(jwt, servlet));
        return respond(result, "/api/v1/work-orders/" + result.value().id());
    }

    @GetMapping("/work-orders/{workOrderId}")
    WorkOrder workOrder(@PathVariable("workOrderId") String workOrderId, @AuthenticationPrincipal Jwt jwt,
                        HttpServletRequest servlet) {
        return orders.get(uuid(workOrderId), Actor.from(jwt, servlet));
    }

    @PatchMapping("/work-orders/{workOrderId}/status")
    @PreAuthorize(MAINTENANCE_READ)
    WorkOrder changeStatus(@PathVariable("workOrderId") String workOrderId, @Valid @RequestBody StatusChange request,
                            @AuthenticationPrincipal Jwt jwt, HttpServletRequest servlet) {
        return orders.status(uuid(workOrderId), request.status(), Actor.from(jwt, servlet));
    }

    @PatchMapping("/work-orders/{workOrderId}/assign")
    @PreAuthorize(OPERATORS)
    WorkOrder assign(@PathVariable("workOrderId") String workOrderId, @Valid @RequestBody TechnicianAssignment request,
                      @AuthenticationPrincipal Jwt jwt, HttpServletRequest servlet) {
        return orders.assign(uuid(workOrderId), request.technicianId(), Actor.from(jwt, servlet));
    }

    @PatchMapping("/work-orders/{workOrderId}/reservation")
    @PreAuthorize(OPERATORS)
    WorkOrder reservation(@PathVariable("workOrderId") String workOrderId, @Valid @RequestBody ReservationUpdate request,
                          @AuthenticationPrincipal Jwt jwt, HttpServletRequest servlet) {
        return orders.reservation(uuid(workOrderId), request, Actor.from(jwt, servlet));
    }

    @GetMapping("/technicians")
    @PreAuthorize(OPERATORS)
    Page<Technician> technicians(@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
                                @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
                                @RequestParam(name = "active", required = false) Boolean active) {
        return orders.technicians(page, size, active);
    }

    @GetMapping("/audit-events")
    @PreAuthorize("hasRole('ADMIN')")
    Page<AuditEvent> auditEvents(@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
                                 @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return audit.list(page, size);
    }

    private static <T> ResponseEntity<T> respond(Creation<T> result, String resource) {
        return result.created() ? ResponseEntity.created(URI.create(resource)).body(result.value())
                : ResponseEntity.ok(result.value());
    }
}
