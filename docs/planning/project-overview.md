# Order Fulfillment Systems Lab
## Detailed Project Action Plan

### Purpose

Build a portfolio project that demonstrates production-style software engineering using:

- Java
- Spring Boot
- Apache Kafka
- PostgreSQL
- Kubernetes
- React
- TypeScript
- REST APIs
- Docker
- CI/CD
- automated testing
- observability
- fault handling
- asynchronous/event-driven architecture

The project should **not pretend to be a real ecommerce business**. Its purpose is to serve as an interactive, technically credible demonstration of an event-driven order fulfillment system.

The frontend should therefore be primarily oriented around:

1. demonstrating normal order processing,
2. deliberately exercising failure and edge cases,
3. exposing what is happening inside the distributed system,
4. making the architecture understandable to recruiters and engineers quickly.

A conventional "fake storefront" should exist only to the extent required to establish that the system can process realistic orders through normal APIs. The main value of the application is the **distributed systems sandbox**, not the fictional store.

Frontend development will be done in VS Code. Backend development will be done in JetBrains IntelliJ Ultimate.

This document, along with its 6 companion architecture/product docs, defines **what** to build and **why**. For **who builds it, in what order, with which Claude model/effort tier, and using what tooling**, see [`execution-plan.md`](execution-plan.md) — the operational reference for AI agent execution. See [`README.md`](README.md) for the full doc index and reading order.

---

# 0. Pinned Technology Decisions

Several choices below were left as "or" options during initial drafting. They are now decided so that independent agents/sessions do not diverge:

| Concern | Decision |
|---|---|
| Backend build tool | Maven (multi-module) |
| Java version | 21 (LTS) |
| Schema migrations | Flyway |
| Kafka local image | `apache/kafka` (native KRaft, no ZooKeeper) |
| Frontend build tool | Vite |
| Frontend server-state/data-fetching | TanStack Query for REST; native `EventSource` for SSE |
| Node version | 22 (LTS) |
| CI | GitHub Actions, path-filtered per service so one service's build doesn't block another |

Do not substitute alternatives (e.g. Gradle, Liquibase, Confluent's Kafka image, Next.js) without updating this table and stating why.

---

# 1. Product Definition

## Working concept

**Order Fulfillment Systems Lab**  
An interactive demonstration of a distributed order-processing platform built with Spring Boot, Kafka, PostgreSQL, Kubernetes, and React/TypeScript.

The system models a simplified order lifecycle:

1. An order is created.
2. Inventory is reserved.
3. Payment is authorized.
4. Fulfillment is requested.
5. A shipment is created.
6. The order reaches a terminal state.

Each major transition is communicated through Kafka events.

The application additionally exposes controlled scenarios that intentionally create failure conditions such as:

- inventory shortages,
- payment rejection,
- consumer outages,
- duplicate event delivery,
- malformed events,
- retryable failures,
- dead-letter processing,
- competing requests for limited inventory,
- service recovery,
- high-volume traffic.

The UI visualizes both business state and infrastructure/event behavior.

---


# 3. Scope Principles

## Do

- Keep the domain small.
- Make Kafka integral to the workflow.
- Make Kubernetes manage genuinely separate runtime components.
- Build reproducible failure scenarios.
- Let the frontend observe actual backend behavior.
- Keep production-style APIs separate from demo/fault-injection APIs.
- Favor a few well-designed services over many superficial microservices.
- Use realistic failure handling.
- Add tests for the behaviors being advertised.
- Document architectural decisions.

## Do not

- Build a large fake ecommerce catalog.
- Spend excessive time on visual storefront polish.
- Create ten or more microservices merely to appear sophisticated.
- Add Redis, Elasticsearch, GraphQL, gRPC, Terraform, service meshes, etc. unless a concrete need emerges.
- Fake Kafka behavior in the frontend.
- Make scenario buttons trigger hard-coded animations.
- Put scenario-specific hacks inside normal business endpoints.
- claim production guarantees that the implementation does not actually provide.

---

# 33. Explicit Non-Goals for Version 1

Do not require:

- real payments,
- real shipping integrations,
- real customer identity,
- production email,
- complex pricing,
- taxes,
- coupons,
- returns,
- recommendations,
- search engines,
- multi-region infrastructure,
- service mesh,
- event sourcing,
- CQRS unless a clear need arises,
- elaborate cloud infrastructure,
- full production Kafka operations.

These are distractions unless the core project is already excellent.

---
