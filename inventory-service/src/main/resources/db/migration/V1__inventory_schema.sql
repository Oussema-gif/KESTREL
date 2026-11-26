CREATE TABLE inventory.spare_parts (
    id uuid PRIMARY KEY,
    sku varchar(200) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    description varchar(1000),
    unit varchar(200) NOT NULL,
    available_quantity integer NOT NULL CHECK (available_quantity >= 0),
    reorder_level integer NOT NULL CHECK (reorder_level >= 0)
);

CREATE TABLE inventory.part_reservations (
    id uuid PRIMARY KEY,
    part_id uuid NOT NULL REFERENCES inventory.spare_parts (id),
    work_order_id uuid NOT NULL,
    request_id uuid NOT NULL UNIQUE,
    quantity integer NOT NULL CHECK (quantity > 0),
    status varchar(16) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
    created_at timestamptz NOT NULL,
    released_at timestamptz,
    release_reason varchar(200),
    CHECK (
        (status = 'RESERVED' AND released_at IS NULL AND release_reason IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND release_reason IS NOT NULL)
    ),
    UNIQUE (id, part_id)
);
CREATE INDEX reservations_work_order ON inventory.part_reservations (work_order_id);

CREATE TABLE inventory.stock_movements (
    id uuid PRIMARY KEY,
    part_id uuid NOT NULL REFERENCES inventory.spare_parts (id),
    reservation_id uuid NOT NULL,
    movement_type varchar(16) NOT NULL CHECK (movement_type IN ('RESERVE', 'RELEASE')),
    quantity integer NOT NULL CHECK (quantity > 0),
    occurred_at timestamptz NOT NULL,
    UNIQUE (reservation_id, movement_type),
    FOREIGN KEY (reservation_id, part_id) REFERENCES inventory.part_reservations (id, part_id)
);
CREATE INDEX stock_movements_part_time ON inventory.stock_movements (part_id, occurred_at);

CREATE TABLE inventory.purchase_orders (
    id uuid PRIMARY KEY,
    part_id uuid NOT NULL REFERENCES inventory.spare_parts (id),
    quantity integer NOT NULL CHECK (quantity > 0),
    supplier_reference varchar(200) NOT NULL,
    status varchar(16) NOT NULL CHECK (status = 'CREATED'),
    created_at timestamptz NOT NULL
);
