# 8.2 — Metrics

[← Structured logging](1-structured-logging.md) · [Next: Scaling →](3-scaling.md)

Logs answer "what happened to *this* order." Metrics answer "how is the system doing." Different
questions, different tools, and conflating them is how you end up counting log lines.

---

## The three pillars, and which two are here

Conventionally: **logs** (discrete events), **metrics** (aggregated numbers over time), **traces**
(one request's path with timings).

This project has the first two. Tracing is deliberately absent — the
[correlation-ID pattern](../patterns/correlation-id-propagation.md) gives *correlation* without spans
or timings, and [Chapter 3](../03-kafka-and-services/3-correlation-ids.md) covers what that does and
does not buy.

**Logs do not aggregate.** "What is the p99 latency of `POST /api/orders`?" is not a log question —
answering it from logs means parsing every line and computing percentiles, which is a data pipeline.
A metrics system computes it as it goes, at a fixed cost per measurement rather than per event.

**Metrics do not particularize.** "Why did order-21873 fail?" is not a metrics question. A counter that
went up tells you nothing about which one.

---

## The stack

```xml
<dependency>...spring-boot-starter-actuator</dependency>
<dependency>...micrometer-registry-prometheus</dependency>
```

Two dependencies in each of the five services.

**Actuator** exposes operational endpoints — health, metrics, info, environment.

**Micrometer** is a metrics *facade*, the SLF4J of metrics: instrument once against its API, choose the
backend by adding a registry dependency. `micrometer-registry-prometheus` is that choice here, and it
adds `/actuator/prometheus`.

**Prometheus** is a pull-based time-series database. It **scrapes** each service on an interval rather
than receiving pushes.

**Grafana** queries Prometheus and draws it.

### Exposure is opt-in

```yaml
management:
  endpoints:
    web:
      exposure:
        # Phase 9 Observability: metrics added to the previously health-only exposure
        include: health,metrics,prometheus
```

Actuator exposes only `health` over HTTP by default, and everything else must be named. That default is
correct — `/actuator/env` prints your entire configuration, `/actuator/heapdump` returns a heap dump —
and this project adds exactly three.

Worth noticing that Phase 8 needed only `health`, and Phase 9 added the other two when there was
something to read them.

### Pull, not push

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: order-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["order-service:8081"]
```

Prometheus pulling has consequences worth understanding, because they are the opposite of most
monitoring systems:

- **A service that stops responding is visibly down.** The scrape fails; that failure is itself a
  signal. A push-based system cannot distinguish "nothing to report" from "gone."
- **The application does not know about Prometheus.** It exposes an endpoint. Nothing is configured
  with a collector address, and nothing breaks when the collector is down.
- **Scrape interval is the resolution.** 5 seconds here — fine-grained for a demo where a scenario
  lasts seconds. Production deployments typically use 15–60s to bound storage.
- **Targets must be discoverable.** `static_configs` works because Compose gives every service a DNS
  name. In Kubernetes you would use service discovery instead.

Note the coupling: `static_configs` naming Compose service names means **this Prometheus config works
under Compose and not under Kubernetes.** A real deployment needs `kubernetes_sd_configs` or the
Prometheus Operator. The observability stack here is a local development tool, and
[Chapter 9](../09-production/README.md) does not deploy it.

---

## What you get for free

The instrumentation that arrives without writing any:

| Metric | Answers |
|---|---|
| `http_server_requests_seconds` | Request rate, latency percentiles, error rate — by URI, method, and status |
| `jvm_memory_used_bytes` | Heap and non-heap by pool |
| `jvm_gc_pause_seconds` | GC frequency and duration |
| `jvm_threads_live_threads` | Thread count |
| `hikaricp_connections_*` | Connection pool usage, pending threads, timeouts |
| `kafka_consumer_*` | Consumer throughput, fetch latency, and **records-lag** |
| `process_cpu_usage`, `system_cpu_usage` | CPU |

Two are worth singling out.

**`hikaricp_connections_pending`** — threads waiting for a database connection. Non-zero means the pool
is the bottleneck, which is a common and easily-misattributed cause of latency. It usually looks like
"the database is slow" when the database is idle.

**`kafka_consumer_records_lag_max`** — the consumer's own view of lag, per partition, exported by the
Kafka client. Distinct from the broker-side lag `ConsumerLagService` reads
([Chapter 5](../05-scenarios-and-frontend/4-observing-the-system.md)): this one is a *metric* for
graphing over time, that one is a *point query* for a scenario to report. Same underlying idea, two
different consumers of it.

**No custom metrics.** No `Counter` for orders created, no `Timer` around reservation. Defensible for a
demo — the built-in instrumentation covers the operational questions, and business counters would be
the first thing to add for a real system. Worth naming as an absence rather than leaving unmentioned.

---

## Grafana

`infrastructure/observability/grafana/` holds a provisioned datasource, a dashboard provider, and one
dashboard — `order-fulfillment-overview.json`.

**Provisioned, not clicked.** Grafana reads YAML at startup and configures itself, so
`docker compose up` produces a working dashboard with no setup. The alternative — a Grafana whose
configuration lives only in its own database — is a dashboard that exists on one machine and nowhere
in version control.

The dashboard is stretch-goal scope in the phase plan (*"Optional: Prometheus, Grafana"*), and treating
it as configuration-as-code is what makes it worth having at all.

---

## The CORS trap

The System Health page from [Chapter 5](../05-scenarios-and-frontend/4-observing-the-system.md) polls
`/actuator/health` from the browser. It did not work, and the reason is the kind of thing that costs an
afternoon:

```yaml
      # Actuator endpoints are served via a separate WebMvcEndpointHandlerMapping that does NOT go
      # through WebConfig's WebMvcConfigurer#addCorsMappings (that only covers regular
      # @RestController endpoints) — so the frontend's browser-side calls to /actuator/health were
      # silently blocked by CORS until this was added, even though curl (which doesn't enforce CORS)
      # showed the endpoint working fine. Found via live browser verification, not curl.
      cors:
        allowed-origin-patterns: "${app.cors.allowed-origin-patterns}"
        allowed-methods: GET
```

**Actuator endpoints do not go through your CORS configuration.** They are served by a separate handler
mapping, and `WebMvcConfigurer#addCorsMappings` covers only `@RestController` endpoints. Actuator has
its own `management.endpoints.web.cors.*` block.

The detail that makes it expensive: *"even though `curl` (which doesn't enforce CORS) showed the
endpoint working fine."* `curl` is not a browser. It sends no `Origin` header and ignores response
headers about who may read the response. **An endpoint can be perfectly functional under `curl` and
completely unusable from a page** — and every instinct says to verify with `curl` first.

*"Found via live browser verification, not curl"* is the practice worth adopting: verify
browser-facing behavior in a browser.

Note also that both blocks reference one property:

```yaml
app:
  cors:
    # Single source of truth for allowed browser origins, consumed by both WebConfig and the
    # actuator CORS block below.
    allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}
```

Two mechanisms, one configured value — so a deployment cannot fix one and forget the other.

> **Primer — [CORS](../technology/http/cors.md)**
> What the same-origin policy protects, preflight requests, why `curl` proves nothing, and when a
> reverse proxy removes the problem entirely.

---

## What Phase 9 delivered against its gate

> A scenario can be followed across services without guessing what happened.

- **One correlation ID** through every HTTP request, event envelope, and log line.
- **Structured JSON** with `service.name` and `correlationId` as queryable fields.
- **Happy-path logging** at every hop — the gap that made the rest of it useless until it was audited.
- **Every 500 logged** with its exception attached.
- **Metrics** for request rate, latency, JVM, connection pool, and consumer lag.
- **A provisioned dashboard**, in version control.

What is absent, and worth being able to say: **no distributed tracing** (correlation without spans or
timings), **no log aggregation** (JSON on stdout, no collector), and **no custom business metrics**.
Each is a scope decision with an obvious next step, and the ECS format and Micrometer facade are
specifically what make those next steps configuration changes rather than rewrites.

---

[← Structured logging](1-structured-logging.md) · [Next: Scaling →](3-scaling.md)
