CREATE TABLE orders (
    id           text PRIMARY KEY,
    customer_id  text NOT NULL,
    status       text NOT NULL,
    total_amount numeric(10,2) NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);

CREATE TABLE order_items (
    id         bigserial PRIMARY KEY,
    order_id   text NOT NULL REFERENCES orders(id),
    sku        text NOT NULL,
    quantity   integer NOT NULL CHECK (quantity >= 1),
    unit_price numeric(10,2) NOT NULL,
    UNIQUE (order_id, sku)
);

CREATE TABLE order_status_history (
    id              bigserial PRIMARY KEY,
    order_id        text NOT NULL REFERENCES orders(id),
    status          text NOT NULL,
    source_event_id uuid NULL,
    occurred_at     timestamptz NOT NULL
);

CREATE INDEX idx_order_status_history_order_occurred ON order_status_history (order_id, occurred_at);
