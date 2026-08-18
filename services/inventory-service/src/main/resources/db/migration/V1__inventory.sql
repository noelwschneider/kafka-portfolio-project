CREATE TABLE inventory_items (
    sku                text PRIMARY KEY,
    display_name       text NOT NULL,
    available_quantity integer NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity  integer NOT NULL CHECK (reserved_quantity >= 0),
    version            bigint NOT NULL,
    updated_at         timestamptz NOT NULL
);

CREATE TABLE inventory_reservations (
    id         text PRIMARY KEY,
    order_id   text NOT NULL,
    sku        text NOT NULL,
    quantity   integer NOT NULL CHECK (quantity >= 1),
    status     text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (order_id, sku)
);
