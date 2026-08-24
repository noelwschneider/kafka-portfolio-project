# 2.1 — The project skeleton

[← Chapter 2](README.md) · [Next: Persistence →](2-persistence.md)

A Maven build, a Spring Boot application, and a configuration discipline that costs nothing now and
saves a rewrite in [Chapter 7](../07-containers-and-kubernetes/README.md).

---

## The problem

You will *end up* with five Spring Boot applications and one library they all use. You do not start
there — [Chapter 1](../01-design-contract/4-sequencing-and-deferrals.md) explains why the sequence is
monolith first, Kafka second, separate services third. But three constraints apply from the first
commit:

- Each service must eventually **build and run independently**.
- They share real code — the event envelope, the error model, the idempotency ledger. Copying it five
  times means five divergent copies within a month.
- It is **one git repository**. Not five, not submodules. That is a pinned project decision.

Maven's multi-module build answers all three: an aggregator POM listing modules, a shared parent
pinning versions, and modules depending on each other by ordinary coordinates.

> **Primer — [Maven multi-module builds](../technology/maven/multi-module-builds.md)**
> Aggregator POMs, `<modules>` vs `dependencyManagement`, BOM imports, `relativePath`, and why a
> library module must not carry `spring-boot-maven-plugin`.

## This project's root POM

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
</parent>

<groupId>com.orderfulfillment</groupId>
<artifactId>order-fulfillment-systems-lab</artifactId>
<version>0.1.0</version>
<packaging>pom</packaging>

<properties>
    <java.version>21</java.version>
</properties>

<modules>
    <module>services/common</module>
    <module>services/order-service</module>
    ...
</modules>
```

That is the *finished* module list. At this point in the build there are two:

```xml
<modules>
    <module>services/common</module>
    <module>services/fulfillment-lab</module>   <!-- the modular monolith -->
</modules>
```

[Chapter 3](../03-kafka-and-services/README.md) replaces the second entry with four service modules. The
aggregator, the parent, the version management, and the `common` module are unchanged by that — which
is the point of setting them up properly now.

One `dependencyManagement` entry is added on top of Spring's, and its comment is worth reading in
full because it records a real Maven wrinkle:

```xml
<!-- Every service's own pom also declares this dependency directly (Maven does not
     propagate dependencyManagement's "import" entries transitively through more than
     one parent hop reliably across all tooling), but centralizing the version here
     keeps the four services' Testcontainers versions from drifting independently. -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.21.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

## What the service POM tells you

A service's dependency list is a readable statement of what the application *is*, because Spring Boot
configures whatever it finds on the classpath:

```xml
<dependency>...common</dependency>                           <!-- our shared library -->
<dependency>...spring-boot-starter-web</dependency>          <!-- HTTP + JSON + embedded server -->
<dependency>...spring-boot-starter-data-jpa</dependency>     <!-- JPA/Hibernate + transactions -->
<dependency>...spring-boot-starter-validation</dependency>   <!-- Bean Validation -->
<dependency>...postgresql (runtime)</dependency>
<dependency>...flyway-core, flyway-database-postgresql</dependency>
```

> **Primer — [Auto-configuration and component scanning](../technology/spring/auto-configuration.md)**
> How starters and conditional auto-configuration work, the `--debug` condition report, property
> precedence and relaxed binding, and the package trap that makes a shared module's beans invisible.

The real POM also carries `spring-boot-starter-kafka`, `spring-boot-starter-actuator`, and
`micrometer-registry-prometheus`. Those belong to Chapters 3 and 8.

## Spring, in one paragraph

Classes declare what they need in their constructor and never construct it; Spring builds the object
graph at startup. `OrderService` takes six collaborators and nothing anywhere calls
`new OrderService(...)`. Note the absence of `@Autowired` — a class with a single constructor needs
none.

```java
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final SkuPriceCatalog priceCatalog;
    // ...
    public OrderService(OrderRepository orderRepository, SkuPriceCatalog priceCatalog, /* … */) {
```

> **Primer — [Dependency injection and stereotypes](../technology/spring/dependency-injection.md)**
> Why constructor injection rather than field injection, what `@Service`/`@Repository`/`@Component`
> actually differ in, `@Bean` methods, singleton scope and the thread-safety consequence, and how to
> read the common startup failures.

---

## Configuration, and a habit worth forming now

Two lines from `application.yml`, both worth copying as habits.

```yaml
spring:
  application:
    name: order-service
```

`spring.application.name` looks cosmetic. It is not — it becomes the `service.name` field in every
structured log line ([Chapter 8](../08-observability-and-scaling/README.md)) and the identity a metrics
scrape is labelled with. Set it on day one.

```yaml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

`${VAR:default}` reads an environment variable and falls back if unset. This is the habit ADR-007
tells you to form from Phase 1 even though nothing verifies it until Phase 7:

> Nothing before Phase 8 proves the services are container-friendly. Configuration must come from
> environment variables and nothing may depend on local filesystem state, or Phase 7 turns into a
> refactor.

Anything that differs between your laptop, Compose, and Kubernetes — broker addresses, database URLs,
allowed CORS origins — gets `${VAR:sensible-local-default}` from the beginning. One line each, and it
is the difference between containerization being a packaging task and a rewrite.

> **Not yet.** The `kafka`, `outbox`, `retention`, `management`, and `logging.structured` blocks in
> the real `application.yml` do not exist yet. Chapters 3, 6, 4, and 8 add them respectively. What
> exists now is `application`, `datasource`, `jpa`, `flyway`, and `server.port`.

---

## The `common` module

A plain library — no `@SpringBootApplication`, no `main`, nothing to run, and deliberately **no**
`spring-boot-maven-plugin` (a fat JAR cannot be depended on as an ordinary library).

What belongs in it is worth being strict about, because a shared module is the easiest place in a
system to accidentally recreate the coupling that service boundaries exist to prevent.

**In:** the event envelope and payload records, the Kafka codec and publisher, topic and event-type
constants, the idempotency ledger, the error model and exception handler, correlation-ID plumbing, ID
generation. Each is either *a frozen contract expressed as code* or *infrastructure with no domain
opinion*.

**Out:** anything domain-specific. There is no shared `Order` class. Order Service's `OrderEntity` and
the `OrderCreatedPayload` in `common` are separate types describing related things, and that
separation is deliberate — the payload is a wire contract, the entity is private storage, and they
are free to diverge.

At this point `common` holds only the error model, correlation-ID plumbing, and `IdGenerator`. The
Kafka half arrives in [Chapter 3](../03-kafka-and-services/README.md) and [Chapter 4](../04-reliability/README.md).

---

## The shape you're aiming at

```
pom.xml                          aggregator, packaging=pom, parent=spring-boot-starter-parent
services/
├── common/pom.xml               packaging=jar, no main class, no boot plugin
│   └── src/main/java/com/orderfulfillment/common/
│       ├── ApiError.java  ApiException.java  GlobalExceptionHandler.java  …
│       └── IdGenerator.java
└── fulfillment-lab/pom.xml      packaging=jar, depends on common, spring-boot-maven-plugin
    ├── src/main/java/com/orderfulfillment/
    │   ├── FulfillmentLabApplication.java
    │   ├── order/          ┐
    │   ├── inventory/      │ four domain packages, one process
    │   ├── payment/        │
    │   └── fulfillment/    ┘
    └── src/main/resources/
        ├── application.yml
        └── db/migration/   V1__orders.sql, V1__inventory.sql, …
```

**Four packages, one application.** The boundaries from
[Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) are real — separate packages,
separate schemas, separate migration files, and a rule that no package reaches into another's
entities. They are just not yet separate *processes*. That is exactly what "modular monolith" means:
the seams are drawn and respected, but everything runs in one JVM where a debugger can step across
the whole workflow.

The entry point is three lines:

```java
@SpringBootApplication
public class FulfillmentLabApplication {
    public static void main(String[] args) { SpringApplication.run(FulfillmentLabApplication.class, args); }
}
```

It sits at `com.orderfulfillment`, above all four domain packages — and above `common`, which is why
`common`'s beans are found by component scanning *now* and will need explicit help after the
Chapter 3 split moves each application class down into its own domain package.

> **A note on naming.** The real repository has no `fulfillment-lab` module — Phase 1's monolith was
> dissolved in Phase 3 and its packages became `services/order-service`,
> `services/inventory-service`, and so on. The name is this guide's, for a module that exists only
> until [Chapter 3](../03-kafka-and-services/README.md). Every *class* named from here on is real and keeps
> its name through the split; only its module changes.

---

[← Chapter 2](README.md) · [Next: Persistence →](2-persistence.md)
