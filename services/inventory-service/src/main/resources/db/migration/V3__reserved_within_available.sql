-- Database-level backstop for Scenario 7's headline invariant: total reserved inventory never
-- exceeds available inventory. V1 gave inventory_items CHECK (available_quantity >= 0) and
-- CHECK (reserved_quantity >= 0) but nothing relating the two, so a purely application-level bug
-- could — and did — write reserved_quantity = 4 against available_quantity = 2 and have the
-- database accept it (docs/agent-reports/phase-3-inventory-concurrency.md §4, §7.2).
--
-- Optimistic locking on `version` remains the primary mechanism. This makes the invariant true by
-- construction rather than by inspection, and turns any future oversell from silent stock
-- corruption into a loud constraint violation. Added to the frozen schema contract via the
-- coordination protocol in docs/planning/execution-plan.md §5 — see docs/db-ownership.md
-- (Inventory Service section) and docs/CHANGELOG-contracts.md.

ALTER TABLE inventory_items
    ADD CONSTRAINT inventory_items_reserved_within_available
    CHECK (reserved_quantity <= available_quantity);
