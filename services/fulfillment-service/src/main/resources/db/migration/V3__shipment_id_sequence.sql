-- Backs IdGenerator.nextShipmentId(). A DB sequence survives service restarts and is safe across
-- multiple instances, unlike the in-memory AtomicLong it replaces (see docs/CHANGELOG-contracts.md).
CREATE SEQUENCE shipment_id_seq START WITH 1000;
