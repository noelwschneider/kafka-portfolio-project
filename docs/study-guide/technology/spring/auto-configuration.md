# Spring Boot: auto-configuration and component scanning

*Referenced from [Chapter 2.1 — The project skeleton](../../02-domain/1-project-skeleton.md).*

---

## Auto-configuration

Spring Boot inspects the classpath at startup and configures what it finds. PostgreSQL driver present
and a `spring.datasource.url` set? It builds a connection pool. Flyway on the classpath? It runs
migrations. Spring Kafka present with a `bootstrap-servers` value? It builds producer and consumer
factories.

This is why a `pom.xml` is worth reading as a statement of what an application *is*:

```xml
<dependency>...spring-boot-starter-web</dependency>          <!-- HTTP + JSON + embedded server -->
<dependency>...spring-boot-starter-data-jpa</dependency>     <!-- JPA/Hibernate + transactions -->
<dependency>...spring-boot-starter-validation</dependency>   <!-- Bean Validation -->
<dependency>...spring-boot-starter-actuator</dependency>     <!-- health, metrics -->
```

A **starter** is a POM with no code of its own that pulls in a curated, version-aligned set of
dependencies. `spring-boot-starter-web` brings Spring MVC, Jackson, an embedded Tomcat, and the
auto-configuration that wires them.

### The rule that makes it safe

Auto-configuration is **conditional and yields to you**. Its classes are covered in annotations like:

- `@ConditionalOnClass` — only if this type is on the classpath
- `@ConditionalOnMissingBean` — **only if you have not defined one yourself**
- `@ConditionalOnProperty` — only if this config value is set

The second is the important one. Define your own `ObjectMapper` bean and Spring Boot steps aside.
Auto-configuration is a set of defaults, not a framework taking over.

### Seeing what it did

Run with `--debug` (or `debug: true`) and Spring Boot prints a **condition evaluation report**: every
auto-configuration considered, which matched, which did not, and why. When something you expected to
be configured is not, this is the first place to look — not the documentation.

## `@SpringBootApplication`

```java
@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

Three annotations in one:

- **`@Configuration`** — this class may itself declare `@Bean` methods.
- **`@EnableAutoConfiguration`** — do the classpath inspection described above.
- **`@ComponentScan`** — find annotated classes to manage.

### Component scanning, and the package trap

`@ComponentScan` with no arguments scans **the annotated class's own package and everything below
it**. That is why the application class conventionally sits at the root of your package tree:

```
com.orderfulfillment.order          ← OrderServiceApplication here
com.orderfulfillment.order.dto      ← scanned
com.orderfulfillment.common         ← NOT scanned
```

`com.orderfulfillment.common` is a *sibling*, not a child. Its `@Component`s are invisible to that
scan, and the symptom is a `NoSuchBeanDefinitionException` for a class that is very obviously
annotated and very obviously on the classpath.

Three ways out, in rough order of preference:

1. **`@SpringBootApplication(scanBasePackages = {"com.orderfulfillment.order", "com.orderfulfillment.common"})`**
   — explicit and obvious.
2. **Put the shared beans behind an auto-configuration** registered in
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. This is how a
   real shared library should do it: consumers get the beans by depending on the artifact, with no
   scanning configuration at all.
3. **Move the application class up** to a common ancestor package. Simple, and it drags in more than
   you meant as the tree grows.

## Configuration properties

Values come from `application.yml`, environment variables, command-line arguments, and several other
sources, in a defined precedence order (later wins): defaults → `application.yml` → profile-specific
YAML → environment variables → command-line arguments.

The `${VAR:default}` form reads an environment variable with a fallback:

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

**Relaxed binding** means `KAFKA_BOOTSTRAP_SERVERS`, `kafka.bootstrap-servers`, and
`kafka.bootstrapServers` are all the same property. That is what makes the environment-variable
override work without any translation layer, and it is why containerized Spring applications are
configured almost entirely through the environment.

### Typed properties

For anything beyond a value or two, bind to a record instead of scattering `@Value`:

```java
@ConfigurationProperties(prefix = "orderfulfillment.outbox")
public record OutboxProperties(int pollIntervalMs, int batchSize, int sendTimeoutMs) { }
```

One type, validated at startup, discoverable by IDE autocomplete, and testable.

## Profiles

`@ActiveProfiles("test")`, `application-production.yml`, `@Profile("!test")` on a bean. A profile
switches configuration and bean sets for an environment.

Useful, and worth using sparingly: a bean that exists in production but not in tests is a bean your
tests do not cover. Prefer configuring the same beans differently over having different beans.
