# Flyway and schema migrations

*Referenced from [Chapter 2.2 — Persistence](../../02-domain/2-persistence.md).*

---

## The problem

A database schema changes over time, and it must change **the same way** on every laptop, in CI, in
Docker Compose, and in production — each starting from whatever version it currently happens to be
at.

That rules out two things people try first:

- **Applying SQL by hand.** Works until there are two environments.
- **Letting the ORM generate the schema** (`ddl-auto: update`). It adds but never removes or alters,
  so it drifts silently, and it cannot express a backfill, a data migration, or a constraint added
  only after cleanup.

## How Flyway works

Numbered SQL scripts, applied in order, with a record of what has been applied.

```
src/main/resources/db/migration/
├── V1__orders.sql
├── V2__processed_events.sql
├── V3__order_id_sequence.sql
└── V4__outbox_events.sql
```

The naming is mechanical: `V` + version + `__` (two underscores) + description + `.sql`.

On startup Flyway reads its `flyway_schema_history` table, compares it against the directory, and
applies whatever is missing, in version order, each in a transaction. That table records the version,
description, the user who ran it, when, how long it took, and a **checksum** of the file.

## The two rules

### 1. An applied migration is immutable

Flyway checksums every file. Edit one that has already been applied anywhere and startup fails with a
checksum mismatch — deliberately, because environments that already ran the old version would never
pick up the change and would silently diverge.

Fix a mistake with a **new** migration. `V5__fix_the_thing_V4_got_wrong.sql` is not embarrassing; it
is how this is supposed to work, and the history is genuinely useful later.

(There is a `repair` command for the case where a migration failed and left no trace. Reach for it
knowing exactly why you need it.)

### 2. Forward-only, in practice

Flyway's free edition has no `down` scripts. Rolling back means writing a new migration that undoes
the change. This is less limiting than it sounds — down-migrations are notoriously undertested, and
in production the realistic recovery for a bad migration is a forward fix or a restore, not a
scripted reversal.

## Versioned vs. repeatable

- **`V`** — versioned. Runs once, ever. Almost everything.
- **`R__`** — repeatable, no version. Re-runs whenever its checksum changes, after all versioned
  migrations. For views, functions, and stored procedures, where "the current definition" is more
  useful than an accumulation of `CREATE OR REPLACE` diffs.

## Multiple independent histories

Flyway can be scoped to a schema:

```yaml
spring:
  flyway:
    schemas: order_service
```

Each schema then gets its own `flyway_schema_history` and its own independent version numbering —
which is why several services in one database can each have a `V1__` with no conflict.

When one JVM must migrate several schemas (a modular monolith with per-module schemas), Spring Boot's
single auto-configured `Flyway` bean is not enough: you construct one `Flyway` instance per schema,
each with its own `schemas` and `locations`, and run them at startup.

## Practical habits

- **One logical change per migration.** Easier to read, easier to reason about when one fails.
- **Migrations are code.** They get reviewed. A `DROP COLUMN` deserves as much attention as any
  deletion.
- **Test against a real database.** [Testcontainers](../../02-domain/5-testing.md) runs your actual
  migrations against actual PostgreSQL on every test run, which means a broken migration fails your
  build rather than your deployment.
- **Seed data can live in a migration** when it is genuinely part of the schema's meaning — a
  reference table, or a fixed demo catalog. Distinguish that from environment-specific data, which
  should not.
