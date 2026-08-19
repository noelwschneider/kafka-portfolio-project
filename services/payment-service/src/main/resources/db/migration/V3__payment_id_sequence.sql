-- Backs IdGenerator.nextPaymentId(). A DB sequence survives service restarts and is safe across
-- multiple instances, unlike the in-memory AtomicLong it replaces (see docs/CHANGELOG-contracts.md).
CREATE SEQUENCE payment_id_seq START WITH 9000;
