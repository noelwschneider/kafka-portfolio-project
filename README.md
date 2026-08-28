**https://fulfillment-demo.noelschneider.com**

An interactive, event-driven order-fulfillment sandbox.

## Technology

| Concern                      | Technology                                          |
| ---------------------------- | --------------------------------------------------- |
| Backend language / framework | Java 21, Spring Boot                                |
| Backend build tool           | Maven                                               |
| Messaging                    | Apache Kafka                                        |
| Database                     | PostgreSQL, one schema per service                  |
| Schema migrations            | Flyway                                              |
| Orchestration                | Kubernetes                                          |
| Frontend build tool          | Vite                                                |
| Frontend framework           | React, TypeScript                                   |
| Frontend data fetching       | TanStack Query for REST; native EventSource for SSE |
| CI                           | GitHub Actions, path-filtered per service           |

## Building locally

From the repo root:

```bash
docker compose up -d --build
```

This builds and starts all 10 containers: 
- Postgres
- Kafka 
- the five backend services (order, inventory, payment, fulfillment, scenario)
- the frontend
- Prometheus
- Grafana

Check everything came up healthy:

```bash
docker compose ps
```

Each backend service exposes health, metrics, and Prometheus endpoints:

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/metrics
curl http://localhost:8081/actuator/prometheus

# port :8081 = order-service
#      :8082 = inventory
#      :8083 = payment
#      :8084 = fulfillment
#      :8085 = scenario
```

Then open the frontend: **http://localhost:5173**

Grafana metrics: **http://localhost:3000**

Tear the stack down when you're done:

```bash
docker compose down
docker compose down -v  # also wipes the Postgres volume if you want a clean slate
```

### Tracing a scenario across services by correlation id

Every request/event in this system carries a `correlationId`

```bash
docker compose logs order-service inventory-service payment-service fulfillment-service scenario-service \
  | grep <correlation-id>
```


## Documentation

All documentation can be found in `docs/`. This includes:
- ADRs and design docs
- OpenAPI specs
- Kafka event schemas
- Work plans and reports
- Agent instructions (along with the contents of `./claude`)
