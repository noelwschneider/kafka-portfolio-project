CREATE TABLE payment_attempts (
    id              text PRIMARY KEY,
    order_id        text NOT NULL,
    idempotency_key uuid NOT NULL UNIQUE,
    status          text NOT NULL,
    amount          numeric(10,2) NOT NULL,
    failure_reason  text NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);
