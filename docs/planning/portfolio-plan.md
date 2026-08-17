# 2. Primary Portfolio Goals

The finished project should provide strong evidence that the developer can:

- design REST APIs with Spring Boot,
- model relational data with PostgreSQL,
- use JPA/Hibernate responsibly,
- implement event-driven workflows with Kafka,
- distinguish synchronous and asynchronous communication,
- reason about eventual consistency,
- design idempotent event consumers,
- handle retries and dead-letter queues,
- reason about message ordering,
- handle concurrency and inventory contention,
- separate service responsibilities,
- containerize services,
- deploy applications to Kubernetes,
- configure health/readiness/liveness behavior,
- use configuration and secrets correctly,
- create automated unit and integration tests,
- build a useful React/TypeScript frontend,
- provide live or near-live operational feedback,
- implement CI/CD,
- add observability,
- explain tradeoffs rather than merely list technologies.

The project should prioritize **depth and explainability over technology count**.

---
# 29. Recruiter-Friendly Presentation

A recruiter should understand the project quickly.

The landing page should clearly state:

> An interactive event-driven order fulfillment system built to demonstrate Java/Spring Boot microservices, Kafka messaging, PostgreSQL persistence, Kubernetes deployment, fault handling, and observable asynchronous workflows.

Immediately offer 3–4 scenarios.

For each scenario display:

**What happens**  
Plain-English description.

**What this demonstrates**  
Engineering concepts.

**Run scenario**  
Triggers real behavior.

---

# 30. Engineer-Friendly Presentation

An engineer should be able to dig deeper.

Provide:

- event IDs,
- partition/offset where available,
- correlation IDs,
- retry counts,
- state transitions,
- service names,
- logs/metrics,
- architecture docs,
- ADRs,
- test evidence.

The project should reward deeper inspection.

---

# 31. Technical Interview Knowledge Checklist

This section is a study guide as much as a build guide.

## Java / Spring Boot

Be able to explain:

- dependency injection,
- controllers/services/repositories,
- DTOs versus entities,
- bean validation,
- exception handling,
- transaction boundaries,
- `@Transactional`,
- JPA persistence context basics,
- optimistic/pessimistic locking,
- configuration profiles,
- Actuator,
- application startup/configuration.

---

## PostgreSQL

Be able to explain:

- primary/foreign keys,
- indexes,
- unique constraints,
- transactions,
- isolation/concurrency,
- locking,
- optimistic version columns,
- schema migrations,
- why database constraints still matter even with application validation.

---

## Kafka

Be able to explain:

- brokers,
- topics,
- partitions,
- offsets,
- producers,
- consumers,
- consumer groups,
- message keys,
- ordering guarantees,
- at-least-once processing,
- idempotency,
- retries,
- DLQs,
- retention,
- consumer recovery,
- why Kafka is not just a queue,
- why Kafka was appropriate here,
- when REST would be simpler and preferable.

You should be able to answer:

> Why is `orderId` a useful Kafka key?

> What happens if a consumer crashes after performing a DB write but before committing its Kafka offset?

> Why must a consumer tolerate duplicate delivery?

> What happens to events while a consumer is offline?

> Why not make the HTTP request wait until shipment creation finishes?

---

## Distributed systems

Be able to explain:

- eventual consistency,
- synchronous vs asynchronous communication,
- failure boundaries,
- duplicate delivery,
- partial failure,
- retries,
- compensation,
- idempotency,
- dual-write problem,
- transactional outbox,
- saga concepts,
- why distributed transactions are difficult.

---

## Kubernetes

Be able to explain:

- pods,
- Deployments,
- Services,
- replicas,
- ConfigMaps,
- Secrets,
- readiness probes,
- liveness probes,
- resource requests/limits,
- rolling deployments,
- HPA,
- stateless vs stateful workloads,
- why Kafka/Postgres operational deployment can be more complex than deploying application pods.

Do not claim Kubernetes is necessary for a small portfolio app. Explain instead that it is used here to demonstrate deployment and scaling behavior of independently deployable services.

---

## React / TypeScript

Be able to explain:

- component boundaries,
- server state vs local UI state,
- typed API contracts,
- asynchronous status updates,
- SSE lifecycle,
- error/loading states,
- scenario progress rendering,
- why the frontend is operationally oriented rather than a fake store.

---

## Testing

Be able to explain:

- unit vs integration vs end-to-end tests,
- why Kafka integration tests should use a real broker/container for critical behavior,
- Testcontainers,
- concurrency testing,
- deterministic test scenarios,
- what should and should not be mocked.

---

# 32. Questions the Project Should Let You Answer in Interviews

By completion, you should have concrete answers for:

1. Why did you use Kafka instead of synchronous REST calls?
2. What guarantees does Kafka provide about ordering?
3. How do you prevent duplicate messages from creating duplicate side effects?
4. How do you handle a consumer being unavailable?
5. How do retries work?
6. What happens after retries are exhausted?
7. How do you handle malformed messages?
8. How do you prevent overselling inventory?
9. How does payment failure release inventory?
10. What is eventual consistency in this application?
11. What happens if the database commit succeeds but Kafka publishing fails?
12. How does the outbox pattern improve that?
13. What do liveness and readiness probes mean?
14. Why would multiple consumer replicas help?
15. How does a Kafka consumer group divide work?
16. What does Kubernetes contribute to this project?
17. Why did you keep the storefront intentionally minimal?
18. How does the demo UI prove actual system behavior rather than simulate it?
19. What metrics/logs are useful when debugging a failed order?
20. What would you change before calling this production-ready?

---

# 39. Recommended "Portfolio Complete" Definition

The project is portfolio-ready when all of the following are true:

- [ ] Order creation works through a real REST endpoint.
- [ ] Order fulfillment is asynchronous.
- [ ] Kafka is genuinely required for the implemented workflow.
- [ ] PostgreSQL persists domain state.
- [ ] At least 3 backend services are independently runnable.
- [ ] Duplicate delivery is handled idempotently.
- [ ] Retry and DLQ behavior exists.
- [ ] Inventory contention is tested.
- [ ] Consumer outage/recovery is demonstrable.
- [ ] React/TypeScript frontend exposes scenarios.
- [ ] Scenario UI reports actual system events.
- [ ] Live/near-live updates work.
- [ ] Service health is visible.
- [ ] Dockerized local setup exists.
- [ ] Kubernetes deployment works.
- [ ] Readiness/liveness probes are configured.
- [ ] CI runs tests/builds.
- [ ] Integration tests use real Postgres/Kafka infrastructure where important.
- [ ] README explains architecture and setup.
- [ ] Architecture diagram exists.
- [ ] Major design decisions are documented.
- [ ] The developer can personally explain each major technology and failure-handling strategy.

---

# **`40. Possible Resume Bullets`**

`Do not use these until they accurately describe the completed implementation.`

`Example:`

- `Developed an event-driven order fulfillment platform using Java, Spring Boot, Apache Kafka, PostgreSQL, and React/TypeScript, separating inventory, payment, and fulfillment workflows through asynchronous domain events.`  
    
- `Implemented idempotent Kafka consumers, bounded retries, dead-letter handling, and correlation-based event tracing to maintain reliable processing and diagnose asynchronous failures.`  
    
- `Designed concurrent inventory reservation logic and integration tests to prevent overselling under competing order requests.`  
    
- `Containerized application services and deployed them to Kubernetes with Deployments, Services, configuration management, health probes, and scalable consumer replicas.`  
    
- `Built an interactive engineering console that triggers reproducible distributed-system scenarios and visualizes service state, event flow, retries, and failure recovery in real time.`

`If the transactional outbox is implemented:`

- `Implemented a transactional outbox pattern to reduce database/Kafka dual-write inconsistency and provide durable event publication.`
