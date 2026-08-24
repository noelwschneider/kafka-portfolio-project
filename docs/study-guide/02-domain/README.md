# Chapter 2 — The domain, built synchronously

**Build history:** Phase 1. Commits `c32e5c6 add backend services` and `8a466ce add frontend application`.

The longest chapter in the guide, and the one everything else stands on. By the end of it you have a
working order-fulfillment system: a real database, real business rules, a real HTTP API, real tests,
and a small React console — running as **one process**, with no Kafka anywhere.

That last part is the point. Phase 1's goal, in the plan's own words, is to *"prove the business
workflow before distributing it."* Distributing a workflow you have not yet got working means
debugging your domain logic and your infrastructure at the same time, unable to tell which one is
lying to you.

---

## Sections

| # | Section | Covers | Status |
|---|---|---|---|
| 1 | [The project skeleton](1-project-skeleton.md) | Maven multi-module, Spring Boot fundamentals, dependency injection, auto-configuration, configuration as environment variables, the `common` module | written |
| 2 | [Persistence](2-persistence.md) | JPA and Hibernate, `open-in-view`, Flyway, `ddl-auto: validate`, the data model table by table, entities, repositories, ID generation | written |
| 3 | [The HTTP layer](3-the-http-layer.md) | Controllers, DTO/entity separation, Bean Validation, the shared error model and global exception handler, CORS | written |
| 4 | [The four domains](4-the-four-domains.md) | Order, inventory, payment and fulfillment business logic; all-or-nothing reservation; optimistic locking; the payment simulator; the temporary synchronous wiring Chapter 3 deletes | written |
| 5 | [Testing](5-testing.md) | Testcontainers, the singleton-container pattern, `@DynamicPropertySource`, what is worth testing here, and the frontend-testing gap | written |
| 6 | [The first frontend](6-the-first-frontend.md) | Vite, React, TypeScript, TanStack Query, the API wrapper, why polling, and what React Router later replaces | written |

---

## What "modular monolith" means here

The service boundaries from [Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) are
real from the first line of code. Four packages, four database schemas, four migration histories, and
a rule that no package touches another's entities or tables.

What is *not* real yet is process separation. Everything runs in one JVM, which means:

- one `mvn spring-boot:run` starts the whole system,
- a debugger can step from `POST /api/orders` all the way to shipment creation,
- and a failure has one stack trace rather than four logs to correlate.

[Chapter 3](../03-kafka-and-services/README.md) puts Kafka between the packages and then pulls them into
separate processes. Because the boundaries were respected from the start, that is a build-file and
wiring change rather than a redesign — which is exactly the payoff ADR-007 predicted.

> **Not yet — and this one is temporary by design.** The workflow in this chapter is wired
> synchronously: `OrderService` calls inventory, which calls payment, which calls fulfillment, and
> `POST /api/orders` returns the *final* status. That contradicts
> `docs/openapi/order-service.yaml`, which says the endpoint returns `PENDING` immediately. The real
> project shipped exactly this deviation and documented it as deliberate and temporary. It is
> scaffolding, not a bug, and [section 4](4-the-four-domains.md) marks precisely which code Chapter 3
> deletes.

---

## Build it yourself

Ordered. Each step is buildable and testable before the next.

**Skeleton** — [section 1](1-project-skeleton.md)

1. Root `pom.xml`: `packaging=pom`, parent `spring-boot-starter-parent`, `java.version=21`, two
   modules (`services/common`, and one application module), and a `dependencyManagement` import of
   the Testcontainers BOM.
2. `services/common`: `packaging=jar`, **no** `spring-boot-maven-plugin`.
3. The application module: `spring-boot-starter-web`, `-data-jpa`, `-validation`, the PostgreSQL
   driver, `flyway-core` + `flyway-database-postgresql`, `spring-boot-starter-test`,
   `testcontainers:junit-jupiter`, `testcontainers:postgresql`, and the boot plugin.
4. `@SpringBootApplication` class at `com.orderfulfillment`, with `order/`, `inventory/`, `payment/`,
   `fulfillment/` beneath it.
5. `application.yml`: `spring.application.name`, datasource, `jpa.open-in-view: false`,
   `jpa.hibernate.ddl-auto: validate`, `server.port`. Every environment-varying value as
   `${VAR:local-default}`.
6. PostgreSQL in Docker, and the four schemas created.

**Persistence** — [section 2](2-persistence.md)

7. Four `db/migration` directories, one per domain, each starting at `V1__`. Write the DDL from
   [section 2](2-persistence.md): `numeric(10,2)` for money, `timestamptz` for time, `CHECK`
   constraints, `UNIQUE (order_id, sku)`, `version bigint` on `inventory_items`, and
   `source_event_id uuid NULL` on `order_status_history`.
8. `V2__seed_data.sql` for the four SKUs. Keep the quantities — they are what the scenarios need.
9. A startup component running one `Flyway` instance per schema. It is deleted in Chapter 3.
10. Entities: `@Enumerated(EnumType.STRING)` on every enum, `protected` no-arg constructors, setters
    only where mutation is legitimate, `@Version` on `InventoryItemEntity`.
11. Repositories as `JpaRepository` interfaces with derived query methods.
12. `V3__*_id_sequence.sql` per schema, and `IdGenerator` in `common` reading them via `JdbcClient`.

**HTTP** — [section 3](3-the-http-layer.md)

13. In `common`: `ApiError`, `ApiException` + `NotFoundException` / `ValidationApiException` /
    `ConflictException`, and `GlobalExceptionHandler` with **all six** handlers — including the 404
    and 405 cases.
14. DTO records per domain in a `dto` subpackage. Never return an entity.
15. Controllers under `/api`. `@Valid` on request bodies, `201 Created` + `Location` from
    `POST /api/orders`.
16. Bean Validation on request records, with `@Valid` on nested collections and an upper bound on
    every string and list.
17. `WebConfig` with CORS driven by `${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}`.

**Domains** — [section 4](4-the-four-domains.md)

18. `SkuPriceCatalog` and server-side pricing. The client never sends a price.
19. `InventoryReservationExecutor`: sum per SKU **first**, check every line, write only if every line
    passes, collect all shortages. Then `release`, filtering on `status = RESERVED`.
20. `PaymentBehaviorStore` (an `AtomicReference`) and the three-mode simulator. No flag on the
    business request.
21. `FulfillmentService.createShipment`, with `shipments.order_id UNIQUE`.
22. The temporary `SynchronousOrderWorkflow`. Mark it as scaffolding in a comment.

**Tests** — [section 5](5-testing.md)

23. `AbstractIntegrationTest`: a **singleton** `PostgreSQLContainer` in a static initializer (not
    `@Container`), `@DynamicPropertySource`, `RANDOM_PORT`, a real HTTP client, and an injected
    `JdbcClient`.
24. Tests for: the happy path, out of stock, payment rejection, duplicate SKUs rejected, unknown SKU
    rejected, an unmapped route returning 404, and concurrent reservations holding
    `reserved ≤ available` — with a conflict counter proving the race actually happened.

**Frontend** — [section 6](6-the-first-frontend.md)

25. `npm create vite@latest -- --template react-ts`, then TanStack Query.
26. `apiFetch` that **throws on `!response.ok`** with a typed `ApiRequestError` carrying the server's
    `ApiError` body. Base URLs from `import.meta.env.VITE_*`.
27. TypeScript types mirroring the OpenAPI spec, with `OrderStatus` as a string-literal union.
28. Three pages taking callback props, a `useState` view switch in `App.tsx`, `useQuery` with
    `refetchInterval: 4000` for the list, and `useMutation` + `invalidateQueries` for create.

**Done when:** a fresh clone, one `docker run` for PostgreSQL, `mvn spring-boot:run`, and `npm run
dev` let you place an order in the browser and watch it reach `FULFILLED`; out-of-stock and
payment-rejection paths both work; and `mvn test` passes from empty.

---

## Next

[Section 1 — The project skeleton](1-project-skeleton.md).
