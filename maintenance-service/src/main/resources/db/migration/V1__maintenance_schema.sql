CREATE TABLE maintenance.users (
    id uuid PRIMARY KEY,
    username varchar(100) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    full_name varchar(120) NOT NULL,
    role varchar(32) NOT NULL CHECK (role IN ('ADMIN', 'MAINTENANCE_OPERATOR', 'TECHNICIAN', 'INVENTORY_OPERATOR')),
    enabled boolean NOT NULL DEFAULT true
);

CREATE TABLE maintenance.technicians (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE REFERENCES maintenance.users (id),
    full_name varchar(120) NOT NULL,
    email varchar(254) NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT true
);

CREATE TABLE maintenance.machines (
    id uuid PRIMARY KEY,
    machine_code varchar(32) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    location varchar(120) NOT NULL,
    model varchar(120) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('RUNNING', 'WARNING', 'CRITICAL', 'OFFLINE', 'MAINTENANCE')),
    observed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE maintenance.alerts (
    id uuid PRIMARY KEY,
    machine_id uuid NOT NULL REFERENCES maintenance.machines (id),
    metric_type varchar(20) NOT NULL CHECK (metric_type IN ('TEMPERATURE', 'VIBRATION', 'PRESSURE', 'ENERGY')),
    severity varchar(16) NOT NULL CHECK (severity IN ('WARNING', 'CRITICAL')),
    observed_value double precision NOT NULL,
    threshold_value double precision NOT NULL,
    message varchar(500) NOT NULL,
    occurred_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'ACKNOWLEDGED', 'RESOLVED')),
    acknowledged_at timestamptz,
    resolved_at timestamptz
);

CREATE UNIQUE INDEX alerts_one_unresolved_metric
    ON maintenance.alerts (machine_id, metric_type)
    WHERE status IN ('ACTIVE', 'ACKNOWLEDGED');
CREATE INDEX alerts_status_occurred ON maintenance.alerts (status, occurred_at DESC, id);

CREATE TABLE maintenance.work_orders (
    id uuid PRIMARY KEY,
    machine_id uuid NOT NULL REFERENCES maintenance.machines (id),
    alert_id uuid UNIQUE REFERENCES maintenance.alerts (id),
    technician_id uuid REFERENCES maintenance.technicians (id),
    title varchar(160) NOT NULL,
    description varchar(2000) NOT NULL,
    priority varchar(16) NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    status varchar(32) NOT NULL CHECK (status IN ('OPEN', 'CHECKING_INVENTORY', 'READY_FOR_MAINTENANCE',
        'WAITING_FOR_PARTS', 'PENDING_RESERVATION', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    part_id uuid,
    part_quantity integer,
    reservation_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT work_order_part_requirement CHECK (
        (part_id IS NULL AND part_quantity IS NULL) OR
        (part_id IS NOT NULL AND part_quantity IS NOT NULL AND part_quantity > 0)
    )
);
CREATE INDEX work_orders_machine ON maintenance.work_orders (machine_id);
CREATE INDEX work_orders_status ON maintenance.work_orders (status, created_at DESC, id);
CREATE INDEX work_orders_technician ON maintenance.work_orders (technician_id, created_at DESC, id);

-- The UUIDs referring to Inventory remain external identifiers.
CREATE TABLE maintenance.reservation_references (
    reservation_id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES maintenance.work_orders (id),
    status varchar(16) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
    UNIQUE (work_order_id, reservation_id)
);
CREATE UNIQUE INDEX work_order_active_reservation
    ON maintenance.reservation_references (work_order_id) WHERE status = 'RESERVED';
ALTER TABLE maintenance.work_orders ADD CONSTRAINT order_current_reservation
    FOREIGN KEY (id, reservation_id) REFERENCES maintenance.reservation_references (work_order_id, reservation_id);

CREATE TABLE maintenance.refresh_tokens (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES maintenance.users (id),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX refresh_tokens_expiry ON maintenance.refresh_tokens (expires_at);

CREATE TABLE maintenance.audit_events (
    id uuid PRIMARY KEY,
    actor_id uuid,
    action varchar(100) NOT NULL,
    target varchar(200),
    result varchar(8) NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE')),
    timestamp timestamptz NOT NULL,
    source varchar(100) NOT NULL
);
CREATE INDEX audit_events_timestamp ON maintenance.audit_events (timestamp DESC, id);
