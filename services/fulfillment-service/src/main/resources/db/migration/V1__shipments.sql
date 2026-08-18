CREATE TABLE shipments (
    id              text PRIMARY KEY,
    order_id        text NOT NULL UNIQUE,
    status          text NOT NULL,
    tracking_number text NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);
