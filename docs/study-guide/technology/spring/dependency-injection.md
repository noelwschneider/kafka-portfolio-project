# Spring: dependency injection and stereotypes

*Referenced from [Chapter 2.1 — The project skeleton](../../02-domain/1-project-skeleton.md).*

---

## The idea

A class declares what it needs and never constructs it. Something else builds the object graph.

```java
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final SkuPriceCatalog priceCatalog;

    public OrderService(OrderRepository orderRepository, SkuPriceCatalog priceCatalog) {
        this.orderRepository = orderRepository;
        this.priceCatalog = priceCatalog;
    }
}
```

Nothing anywhere calls `new OrderService(...)`. At startup Spring sees the class is a `@Service`,
inspects its constructor, finds a **bean** for each parameter type, and wires it. A *bean* is just an
object Spring manages.

## Why it's worth the indirection

**Testability.** A test can construct `OrderService` with a stub repository. If the class constructed
its own dependencies, the only way to test it would be to make the real ones work.

**One place decides wiring.** Swapping an implementation is a configuration change, not an edit
scattered across every call site.

**Lifecycle management.** Beans are singletons by default, created once at startup, with a defined
initialization and shutdown order — which matters for connection pools, listener containers, and
anything that needs to close cleanly.

The cost, and it is real: **a lot happens that you did not write**. A failure at startup is a failure
in a graph you never drew. Getting comfortable reading Spring's startup errors is part of the job.

## Constructor injection, always

Three forms exist. Only one is a good idea.

```java
// Constructor injection — use this
private final OrderRepository repository;
public OrderService(OrderRepository repository) { this.repository = repository; }

// Field injection — avoid
@Autowired private OrderRepository repository;

// Setter injection — rarely justified
@Autowired public void setRepository(OrderRepository r) { this.repository = r; }
```

Constructor injection wins on four counts:

- **Fields can be `final`.** The object is immutable and complete once constructed.
- **The object is never in an invalid state.** Field injection produces an instance whose fields are
  null until Spring finishes with it.
- **It can be constructed in a plain unit test**, with no Spring at all.
- **It makes bloat visible.** A constructor with nine parameters is uncomfortable to look at, and it
  should be — that class is doing too much. Field injection hides the same problem behind nine tidy
  annotations.

Since Spring 4.3, **a class with a single constructor needs no `@Autowired`**. The constructor is
implicitly the injection point. Most modern Spring code has no `@Autowired` in it at all.

## Stereotypes

`@Component` marks a class as something Spring should manage. The rest are specializations —
functionally near-identical, but they document intent and some add behavior:

| Annotation | Use for | Extra behavior |
|---|---|---|
| `@Component` | Anything that doesn't fit below | — |
| `@Service` | Business logic | None; purely intent |
| `@Repository` | Data access | Translates persistence exceptions into Spring's `DataAccessException` hierarchy |
| `@RestController` | HTTP endpoints | `@Controller` + `@ResponseBody` |
| `@Configuration` | Bean definitions via `@Bean` methods | Proxies the class so `@Bean` methods return the singleton on repeat calls |

Spring Data repositories are the exception to all of this: `OrderRepository` is an **interface** with
no annotation and no implementation. Spring Data generates the implementation at startup. See
[Spring Data repositories](data-repositories.md).

## `@Bean` methods, for objects you don't own

Stereotypes need a class you can annotate. For third-party types, declare them in a
`@Configuration` class:

```java
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name("orders.events").partitions(3).replicas(1).build();
    }
}
```

The method name becomes the bean name, and the return value becomes the bean. Method parameters are
themselves injected, so `@Bean` methods can depend on other beans.

## Scopes and the one that bites

Beans are **singletons** by default — one instance for the whole application, shared by every
injector.

The consequence people trip over: **a singleton must be thread-safe.** Two HTTP requests handled on
different threads share the same `OrderService` instance. Mutable instance state on a Spring bean is
shared mutable state across concurrent requests. The usual answer is not to have any: keep beans
stateless, and pass per-request data as method arguments.

Where per-request state is genuinely needed, Spring has `@Scope("request")` and `@Scope("prototype")`
— but reach for them knowing why the default did not work, because both come with their own
surprises when injected into a singleton.

## Common startup failures

| Message | Usual cause |
|---|---|
| `NoSuchBeanDefinitionException` | The class isn't annotated, or isn't inside the component-scanned package tree. See [auto-configuration](auto-configuration.md). |
| `NoUniqueBeanDefinitionException` | Two beans match one type. Use `@Qualifier`, or mark one `@Primary`. |
| `BeanCurrentlyInCreationException` | A circular dependency (A needs B needs A). Almost always a design problem; extract the shared part into a third bean. |
