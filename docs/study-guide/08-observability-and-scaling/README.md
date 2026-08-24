# Chapter 8 — Observability and scaling

**Build history:** Phase 9 (`a1cdcf2 observability`), Phase 10 (`55d55d7 scaling`), and Sprint 2's
HorizontalPodAutoscaler (`6212383`).

Two phases about being able to *see* the system, and one about making it bigger. They belong together
because you cannot responsibly do the second without the first — Phase 10's most valuable outputs are
measurements, and measurements need instrumentation.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Structured logging](1-structured-logging.md) | ECS over an encoder library, the correlation ID finally becoming a field, and the audit that found four of five services logging nothing on a successful run |
| 2 | [Metrics](2-metrics.md) | Actuator and Micrometer, pull-based scraping, what you get for free, provisioned Grafana, and the Actuator CORS trap |
| 3 | [Scaling](3-scaling.md) | Partitions as the parallelism ceiling, Scenario 8, what Phase 10 could and could not measure, and what scaling does not fix |
| 4 | [The autoscaler](4-the-autoscaler.md) | The HPA, why `maxReplicas: 3` is architecture rather than budget, utilization as a fraction of *request*, and the incident where the autoscaler caused a second outage |

---

## The exit criteria

**Phase 9:** *A scenario can be followed across services without guessing what happened.*
**Phase 10:** *The project can demonstrate **why** Kafka consumer groups and Kubernetes scaling are
useful.*

Phase 10's phrasing is the interesting one. Not "the project scales" — demonstrate *why the mechanism
is useful*, which requires showing where it helps **and where it stops helping**. That is why the
partition ceiling matters more here than any throughput number.

---

## Four ideas worth carrying out

**A perfect mechanism attached to nothing is worth nothing.** Correlation-ID propagation was correct
from Chapter 3. Structured logging rendered it correctly in Phase 9. And a live scenario run still
produced *zero* log output in four of five services, because the only lines the consumers had were on
branches a successful run never takes. The audit caught it; the configuration change alone would not
have.

**`curl` is not a browser.** An endpoint can work perfectly under `curl` and be completely unusable
from a page, because `curl` sends no `Origin` header and ignores CORS entirely. Verify browser-facing
behavior in a browser.

**The partition count is the parallelism ceiling.** A Kafka consumer group can never usefully run more
consumers than partitions. "Just add pods" is not an answer — and raising the partition count changes
which partition existing keys hash to, so it is not a free fix either.

**Startup CPU is not load.** An autoscaler with no scale-up stabilization read five JVMs cold-starting
after a deploy as sustained demand, added replicas at the moment the box had least memory, and caused a
second outage on top of the first. Every decision it made was correct given its inputs.

---

## Build it yourself

**Logging** — [section 1](1-structured-logging.md)

1. `logging.structured.format.console: ecs` in every service. No dependency, no `logback-spring.xml`.
2. Confirm `spring.application.name` is set — it becomes `service.name`.
3. **Audit your log call sites.** Count them, run a real end-to-end workflow, and check what actually
   came out per service. If a service produced nothing, its only lines are on branches the happy path
   never takes.
4. Add one `INFO` per consumer happy path, **inside** the correlation scope, right after the event is
   confirmed relevant. Plus the first HTTP hop and wherever a workflow's correlation ID is minted.
5. Ensure the catch-all exception handler logs its exception: `log.error(…, ex)`.
6. Verify: `docker compose logs | grep <correlation-id>` returns the whole workflow across all five
   services.

**Metrics** — [section 2](2-metrics.md)

7. `spring-boot-starter-actuator` + `micrometer-registry-prometheus` per service.
8. `management.endpoints.web.exposure.include: health,metrics,prometheus` — nothing more.
9. `prometheus.yml` scraping every service's `/actuator/prometheus`.
10. Grafana **provisioned from files** — datasource, dashboard provider, and the dashboard JSON, all in
    version control.
11. `management.endpoints.web.cors.allowed-origin-patterns` — Actuator has its **own** CORS block and
    does not use `WebConfig`'s. Point both at one property.

**Scaling** — [section 3](3-scaling.md)

12. A high-volume scenario reading **real broker-side lag** via `AdminClient`, not a self-reported
    counter.
13. `kubectl scale deployment/inventory-service --replicas=2`, run the scenario, and record throughput,
    latency, and lag at 1 and 2 replicas. Watch the rebalance.
14. Try 3, and **record what happens** — including if your hardware runs out first. That number is
    worth having.

**Autoscaler** — [section 4](4-the-autoscaler.md)

15. `metrics-server` (kind only — k3s bundles one).
16. An `autoscaling/v2` HPA on the consumer that actually saturates, targeting the resource that
    actually saturates. `maxReplicas` = **the partition count**, and say so in a comment.
17. `averageUtilization` below 100 and relative to the **request** — know which number you are a
    percentage of.
18. `scaleDown.stabilizationWindowSeconds: 120` against flapping, and
    `scaleUp.stabilizationWindowSeconds: 60` so a cold-start CPU spike is not read as demand.
19. Verify **both directions** and keep the `kubectl describe hpa` events as evidence.

**Done when:** one grep returns a whole workflow across five services; Grafana comes up configured from
a fresh clone; the health page works *in a browser*; a scenario run reports real consumer lag; scaling
Inventory Service to 2 measurably drains the backlog faster; and the HPA scales up under load and back
down afterwards, with the controller's own events as proof.

---

## Next

[Section 1 — Structured logging](1-structured-logging.md).
