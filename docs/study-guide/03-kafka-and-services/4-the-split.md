# 3.4 — The split

[← Correlation IDs](3-correlation-ids.md) · [Chapter 3 ↑](README.md)

One process becomes four. This is the part that sounds like the hard bit and turns out not to be —
which is the whole argument of ADR-007 and the reason the build order is what it is.

---

## What actually has to change

Once the four domains communicate only through Kafka, they no longer call each other's methods. The
only thing keeping them in one JVM is that they happen to be in one JVM.

So the extraction is mechanical:

1. **Four modules instead of one.** `services/order-service`, `services/inventory-service`,
   `services/payment-service`, `services/fulfillment-service`, each with its own `pom.xml`, each
   depending on `common`, each with the `spring-boot-maven-plugin`.
2. **Four application classes**, each moving down from `com.orderfulfillment` to
   `com.orderfulfillment.<domain>` so its component scan covers only its own package.
3. **Four `application.yml` files**, each with its own `spring.application.name`, its own
   `server.port` (8081–8084), and its own `spring.flyway.schemas`.
4. **The migrations move with their domain**, into each module's own
   `src/main/resources/db/migration`.
5. **Delete `SynchronousOrderWorkflow`.** Nothing has called it since
   [section 2](2-producing-and-consuming.md).

The domain classes themselves — `OrderService`, `InventoryService`, `PaymentService`,
`FulfillmentService`, every entity, every repository, every controller, every listener — move between
modules **unchanged**. That is the payoff being collected.

## The two things that get simpler

**Flyway.** [Chapter 2](../02-domain/2-persistence.md) needed a hand-written runner because one JVM
migrated four schemas. Now each JVM owns one, and Spring Boot's ordinary auto-configuration is enough:

```yaml
spring:
  flyway:
    schemas: order_service
    # Phase 3 simplification: Spring Boot's ordinary built-in Flyway auto-configuration is enough
    # now that each service's JVM only ever migrates its own schema — the Phase 1/2 multi-schema
    # SchemaMigrationRunner existed only because one JVM drove four schemas at once.
```

Delete the runner.

**Component scanning gets a wrinkle, not a simplification.** Each application class now sits at
`com.orderfulfillment.order` and no longer scans `com.orderfulfillment.common`, which is a sibling.
`EventCodec`, `EventPublisher`, `GlobalExceptionHandler`, and `CorrelationIdFilter` all become
invisible until you say otherwise — via `scanBasePackages`, or by registering `common`'s beans as an
auto-configuration. See the
[auto-configuration primer](../technology/spring/auto-configuration.md) for both options.

## What stays shared

One PostgreSQL server, four schemas. ADR-004 rejected a database container per service for local
development as disproportionate:

> four database containers to start, four connection configurations, four sets of credentials, and
> four times the memory, all to enforce a boundary that one schema per service plus a code review
> already enforces.

**Nothing in the code changes if you later split the server**, because no query ever crosses a schema.
The boundary is real; the deployment topology is a configuration detail. Being able to say that — and
to point at *why* it is true — is a much better answer than either "we share a database" or "we have
four databases."

Also shared: the `common` module, and one Kafka cluster.

---

## Testing across a boundary

This is the genuinely interesting problem the split creates, and the project's answer is worth
copying.

**The problem.** `OrderServiceIntegrationTest` used to create an order and assert it reached
`FULFILLED`, because all four domains were in the JVM under test. Now they are not. Order Service
alone cannot fulfil anything.

Three options:

- **Start all four services in the test.** Highest fidelity, and it makes every service's test suite
  depend on every other service's code — recreating in the tests exactly the coupling the split
  removed.
- **Mock the Kafka interactions.** Fast, and it proves nothing about the wire format, which is the
  contract that actually matters.
- **Start one service, and simulate the others by publishing the events they would have published.**

The third is what this project does:

> unlike the Phase 1/2 monolith's single integration-test base that exercised all four domains in one
> JVM, this base only ever starts Order Service itself — Inventory/Payment/Fulfillment's own
> reactions are simulated by publishing the same wire-format events they would have produced, using
> the same `EventPublisher` bean this service uses for its own outbound events, so the JSON shape is
> identical to what a real upstream service would send.

The detail that makes it work is **using the production `EventPublisher`** rather than hand-writing
test JSON. A hand-written fixture drifts from the envelope the moment anything changes; publishing
through the same code path the real producer uses means the test exercises the actual frozen contract.

So each service's tests prove: *given these events on the wire, this service does the right thing and
publishes these events in response.* Which is precisely what its contract says, and no more.

The base class also gains a real Kafka broker alongside PostgreSQL:

```java
KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));
KAFKA.start();
```

and a **raw consumer** for asserting on what the service published:

```java
/** Raw consumer for asserting what this service published, independent of its own listener
 * container's consumer group. */
Consumer<String, String> rawConsumer(String topic) {
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // ...
}
```

A **unique group per call** is the key detail. A fixed group would compete with the application's own
consumers for partitions and would carry committed offsets between tests. A fresh random group reads
the whole topic from the beginning and interferes with nothing.

And one deliberate escape hatch:

```java
/**
 * For publishing records {@link EventPublisher} deliberately cannot produce — an envelope with
 * an eventVersion the codec rejects, or a payload that will not deserialize. Phase 4's
 * poison-message scenario needs a genuinely malformed record on the wire, not a mocked failure.
 */
@Autowired
KafkaTemplate<String, String> kafkaTemplate;
```

*"A genuinely malformed record on the wire, not a mocked failure"* is the standard the whole project
holds itself to, applied to tests.

---

## The frontend

Five base URLs instead of one:

```ts
export const ORDER_SERVICE_BASE_URL = import.meta.env.VITE_ORDER_SERVICE_URL ?? 'http://localhost:8081';
export const INVENTORY_SERVICE_BASE_URL = import.meta.env.VITE_INVENTORY_SERVICE_URL ?? 'http://localhost:8082';
// …
```

This is where the client feels ADR-004 directly: the order list comes from one service and the SKU
catalog from another, and nothing joins them but the browser. It is also why every service needs CORS
configured — five origins in development.

> **Where this changes.** [Chapter 9](../09-production/README.md) puts all five behind a single hostname with
> `/svc/{service}/…` path prefixes, at which point there is no cross-origin request at all and CORS
> exists only for local development. `docs/architecture-diagram.md` calls this out as *"a deployment
> property rather than an architectural one"* — the arrows are the same either way.

---

## The exit criteria, and what they actually prove

Phase 3's criteria are behavioral rather than structural, which is the right way to write them:

> - services can be independently stopped/restarted,
> - order processing still works after recovery,
> - service boundaries are understandable.

The second is the one to actually test, by hand, once: **stop Inventory Service, create an order,
watch it sit at `PENDING`, start Inventory Service, watch it complete.**

That single exercise demonstrates the entire argument for the architecture. In the synchronous version
of [Chapter 2](../02-domain/4-the-four-domains.md), an inventory outage would have *failed* that order
with a 500. Here it *delayed* it, the record waited on the topic, and the work resumed on recovery
with nothing lost and nobody notified.

It is also Scenario 5 (Consumer Outage and Recovery) in embryo — [Chapter 4](../04-reliability/README.md)
turns it into a controllable, repeatable demonstration rather than something you do by hand with two
terminals.

---

[← Correlation IDs](3-correlation-ids.md) · [Chapter 3 ↑](README.md) · [Chapter 4 — Reliability →](../04-reliability/README.md)
