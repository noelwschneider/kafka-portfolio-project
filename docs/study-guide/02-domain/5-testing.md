# 2.5 — Testing

[← The four domains](4-the-four-domains.md) · [Next: The first frontend →](6-the-first-frontend.md)

Phase 1's exit criteria end with *"tests protect domain rules."* This section is about what that
means concretely, and about one library that changes the economics of integration testing entirely.

---

## The problem: what does a passing test prove?

The classic pyramid says many unit tests, fewer integration tests, fewest end-to-end. The reasoning is
economic — integration tests were historically slow, flaky, and needed infrastructure somebody had to
maintain.

That reasoning has a specific consequence people underrate. If your persistence layer is only ever
tested against mocks, then **every passing test is a statement about your mocks**. A repository test
with a mocked `EntityManager` proves your code calls the methods you think it calls. It cannot tell
you that:

- the entity mapping matches the migration,
- `@Version` actually produces the `WHERE version = ?` clause you are relying on,
- a `UNIQUE` constraint fires when you expect,
- `numeric(10,2)` round-trips your `BigDecimal` unchanged,
- your Flyway migrations apply cleanly in order from empty.

For a system whose interesting behavior *is* concurrency and persistence semantics, mocked tests
would test almost nothing that matters.

## The technology: Testcontainers

Testcontainers starts **real Docker containers** for the duration of a test run and hands you their
connection details. A real PostgreSQL. A real Kafka broker. Not an in-memory substitute, not a mock —
the same image you run in production.

That collapses the old trade-off. An integration test still costs seconds rather than milliseconds,
but it is no longer flaky, no longer dependent on shared infrastructure, and no longer a lie about
what it proves. The pyramid flattens: this project has **53 test classes, and the overwhelming
majority are integration tests**, because that is where the truth is.

---

## The base class

Every service has an `AbstractIntegrationTest`. Here is the shape, with the parts that matter:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("orderfulfillment")
                .withUsername("orderfulfillment")
                .withPassword("orderfulfillment");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    RestTestClient client;

    @BeforeEach
    void initClient() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
```

Four decisions in there, each worth understanding.

### `@DynamicPropertySource` — the chicken-and-egg fix

Testcontainers assigns a **random host port** so parallel runs and local development do not collide.
But Spring needs `spring.datasource.url` when it builds the context, and the port is not known until
the container starts.

`@DynamicPropertySource` resolves this: it runs before the context is created and registers property
*suppliers* (note `POSTGRES::getJdbcUrl` — a method reference, not a value) that Spring calls at the
moment it needs them.

### A static initializer, not `@Container`

Testcontainers offers `@Testcontainers` + `@Container` annotations that manage lifecycle for you. This
project deliberately does not use them, and the comment explains why:

> that annotation pair restarts containers between test classes and reassigns ports, which can strand
> a cached Spring test context on a dead port. A singleton container started once in a static
> initializer (reaped by Testcontainers' Ryuk at JVM exit) avoids this.

This is a genuinely useful piece of hard-won knowledge. Spring's test framework **caches application
contexts** across test classes with identical configuration — a large performance win, since starting
a Spring context is the expensive part. But a cached context holds a connection pool pointed at the
port the container had *then*. Restart the container, get a new port, and the cached context is
pointing at nothing.

The **singleton container pattern** — one static container, started once, never stopped — matches the
context cache's lifetime. Cleanup is handled by **Ryuk**, a Testcontainers sidecar container that
reaps everything when the JVM exits, including after a crash or a killed test run.

### `RANDOM_PORT` and a real HTTP client

`WebEnvironment.RANDOM_PORT` starts a real embedded server on a random port, injected via
`@LocalServerPort`. Tests then make **real HTTP requests** through `RestTestClient`.

The alternative, `MockMvc`, exercises the Spring MVC stack without a server — faster, but it does not
test JSON serialization over the wire, does not test the servlet filter chain, and cannot test SSE at
all. Since [Chapter 5](../05-scenarios-and-frontend/README.md) needs SSE, the real-server choice pays for
itself.

### `JdbcClient` for assertions

```java
/** Direct reads of the processed_events ledger, which has no JPA entity by design. */
@Autowired
JdbcClient jdbcClient;
```

Asserting on tables that have no entity means plain SQL. This becomes essential from
[Chapter 4](../04-reliability/README.md) onward, where the most important assertions are about the ledger and
the outbox — infrastructure tables the application code deliberately does not map.

---

## What's worth testing at this stage

The tests that exist for Phase 1's behavior fall into four groups.

**Domain rules with real persistence.** `OrderServiceIntegrationTest` and
`InventoryServiceIntegrationTest`: create an order and check the total is computed from the catalog;
reserve stock and check the counters; reserve more than exists and check nothing was written.

**Concurrency, proven rather than asserted.** `InventoryServiceOptimisticLockTest` and
`InventoryConcurrencyIntegrationTest` fire genuinely simultaneous reservations at the same SKU and
assert the invariant `reserved ≤ available` holds.

There is a subtlety here worth stealing. A concurrency test that only asserts the invariant can pass
because *nothing ever raced* — the threads happened not to overlap, the invariant held trivially, and
you learned nothing. This project defends against that by counting conflicts:

```java
/**
 * Counts real {@code @Version} conflicts observed against the database. Exposed so
 * {@code InventoryConcurrencyIntegrationTest} can assert the conflict path was genuinely
 * exercised rather than assert an invariant that held only because nothing ever raced.
 */
private final AtomicLong optimisticLockConflicts = new AtomicLong();
```

**Assert that the dangerous path was taken, not just that the outcome was fine.** That principle
generalizes to every test of a race, a retry, or a fallback.

**Pure unit tests, where there is genuinely nothing to integrate.** `OrderStatusTest`,
`PaymentServiceTest`, `CreateOrderRequestValidationTest`. Validation annotations and enum logic need
no database, and testing them with one would be waste.

**Error contract tests.** That a bad request produces a 400 with the right `code`, that a missing
order produces `ORDER_NOT_FOUND`, that an unmapped route produces a 404 rather than a 500. The last
one exists as `UnmappedRouteIntegrationTest` — written *after* the bug in
[section 3](3-the-http-layer.md), which is the usual way regression tests come into being.

---

## Two habits worth forming here

**Test names should state the rule.** `reserveFailsWhenAnyLineIsShort` tells you what broke from the
failure report alone. `testReserve2` requires opening the file.

**A test for a bug goes in before the fix.** Every "we got this wrong" callout in this guide
corresponds to a test in the repository — `OrderOutOfOrderTransitionIntegrationTest`,
`UnmappedRouteIntegrationTest`, `OrderStreamBrokenConnectionIntegrationTest`. The test is the durable
part of the fix; without it the bug is one refactor away from returning.

---

## An honest gap

**There are no frontend tests.** No component tests, no browser tests, nothing. The `frontend/`
directory contains no test files at all.

That is a real gap rather than a deliberate scope decision, and it is worth knowing before anyone asks
about the project's testing story. The backend's coverage is genuinely strong — 53 integration test
classes against real infrastructure — and the frontend has none, which is a lopsided answer to "how do
you test this?"

The honest framing: the backend is where the project's interesting behavior lives, testing effort went
there, and the frontend was verified manually and in the browser. That is a defensible allocation and
an undefended flank, both at once.

---

[← The four domains](4-the-four-domains.md) · [Next: The first frontend →](6-the-first-frontend.md)
