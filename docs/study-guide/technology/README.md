# Technology primers

General technology explanation, independent of this project. The chapters stay lean and link here at
the point each concept first matters.

Each page assumes genuine unfamiliarity rather than jargon-recognition, and each is readable on its
own — you can arrive from a chapter, read one page, and go back.

A concept earns a page here when it needs more depth than a chapter's flow can carry, or when two or
more chapters need it. Project-*specific* implementation patterns live in
[`../patterns/`](../patterns/) instead; the dividing line is whether you would explain it the same way
on a different project.

---

## Java & build

| Page | Covers |
|---|---|
| [Maven multi-module builds](maven/multi-module-builds.md) | Aggregator POMs, `<modules>` vs `dependencyManagement`, BOM imports, `relativePath`, packaging application vs. library modules, what belongs in a shared module |

## Spring

| Page | Covers |
|---|---|
| [Dependency injection and stereotypes](spring/dependency-injection.md) | Constructor vs. field injection, `@Service`/`@Repository`/`@Component`, `@Bean` methods, singleton scope and thread safety, common startup failures |
| [Auto-configuration and component scanning](spring/auto-configuration.md) | Starters, conditional configuration, the `--debug` condition report, property precedence and relaxed binding, `@ConfigurationProperties`, profiles, the shared-module package trap |
| [Spring MVC controllers and exception handling](spring/web-mvc.md) | The annotation set, path-matching precedence, status codes, `@RestControllerAdvice`, the four exceptions most projects mishandle, `void` handlers, Jackson behavior |
| [Bean Validation](spring/bean-validation.md) | The constraint vocabulary, `@Valid` recursion, boxed vs. primitive types, bounding untrusted input, validating outside controllers |
| [Spring Data repositories](spring/data-repositories.md) | Derived query grammar, `@Query`, pagination and the hidden count query, `@Lock`, the `@Transactional` self-invocation trap, when to drop to plain SQL |

## Persistence

| Page | Covers |
|---|---|
| [JPA and Hibernate](jpa/hibernate-basics.md) | Entities, the persistence context, dirty checking, lazy loading, the `@Enumerated` ORDINAL footgun, N+1, detached entities, `ddl-auto` |
| [Flyway and schema migrations](flyway/migrations.md) | File naming, checksums and immutability, forward-only, versioned vs. repeatable, multiple independent histories |
| [PostgreSQL column types](postgres/column-types.md) | `numeric` vs. float for money, `timestamptz` vs. `timestamp`, `CHECK`/`UNIQUE` vs. application validation, foreign keys across boundaries, nullability as information |

## Messaging

| Page | Covers |
|---|---|
| [Messaging: queues vs. logs](kafka/log-vs-queue.md) | Synchronous vs. asynchronous, what a queue is good at, what a log buys, honest guidance on choosing |
| [Kafka: topics, partitions, keys, and offsets](kafka/topics-partitions-keys.md) | The record model, partitions as ordering *and* parallelism, keys and hashing, offsets and commits, consumer groups, rebalancing, replication, `acks`, KRaft |
| [Spring for Apache Kafka](kafka/spring-kafka.md) | What the starter auto-configures, serializer choices, `auto-offset-reset`, producing and offset commits, `@KafkaListener` and the listener container, concurrency, testing |

## Containers & orchestration

| Page | Covers |
|---|---|
| [Docker: images, layers, and multi-stage builds](docker/images-and-layers.md) | Layer caching and instruction order, multi-stage builds, base-image choice, build context and `.dockerignore`, exec vs. shell entrypoint, layered JARs, `ARG` vs `ENV` |
| [Kubernetes: the object model](kubernetes/objects.md) | Reconciliation, Pods and Deployments, Services and their types, ConfigMaps and Secrets, namespaces, requests vs. limits, reading cluster state |
| [Kubernetes: health probes](kubernetes/probes.md) | The three probes, why a dependency check in liveness causes restart storms, timing fields, probe types and costs, startup probes, Spring Boot health groups |

## Frontend

| Page | Covers |
|---|---|
| [React: components, state, and hooks](react/components-and-hooks.md) | The rendering model, `useState` and immutable updates, props and lifting state up, list keys, `useEffect` and cleanup, rules of hooks, custom hooks |
| [TanStack Query](react/tanstack-query.md) | Server state vs. client state, query keys and the cache, mutations and `invalidateQueries`, `staleTime` vs `gcTime`, polling vs. pushing, error handling |

## HTTP & the web

| Page | Covers |
|---|---|
| [OpenAPI](http/openapi.md) | Generated-from-code vs. written-first, what a schema cannot say, `$ref`/`operationId`/`servers`, how a spec drifts |
| [Server-Sent Events](http/server-sent-events.md) | The wire format, `EventSource` and automatic reconnection, SSE vs. WebSockets, the HTTP/1.1 connection limit, keep-alives, `SseEmitter` mechanics and its traps, deployment considerations |
| [CORS](http/cors.md) | The same-origin policy, preflight, why `curl` proves nothing, the Actuator handler-mapping trap, when a proxy is the better answer |

## Concepts

| Page | Covers |
|---|---|
| [Finite state machines](concepts/state-machines.md) | The formalism, why an explicit transition set matters, encoding the table so code consults it, consistency checks, rejecting vs. deferring |
