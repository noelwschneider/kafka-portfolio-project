# 7.1 — Containers and Compose

[← Chapter 7](README.md) · [Next: Kubernetes manifests →](2-kubernetes-manifests.md)

Phase 7's exit criterion is one sentence, and it is a good one:

> A fresh clone can be started using documented commands.

---

## Why now, and not earlier

[ADR-007](../01-design-contract/4-sequencing-and-deferrals.md) held containers back until the service
boundaries were stable — and warned about the cost of doing so:

> Nothing before Phase 8 proves the services are container-friendly. Configuration must come from
> environment variables and nothing may depend on local filesystem state, **or Phase 7 turns into a
> refactor**.

That is why [Chapter 2](../02-domain/1-project-skeleton.md) insisted on `${VAR:local-default}` from the
first `application.yml`. The bill comes due here, and it is small:

```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderfulfillment
  KAFKA_BOOTSTRAP_SERVERS: kafka:29092
```

Two variables per service. Everything else already had a sensible default. **Containerization is a
packaging exercise rather than a rewrite**, exactly as ADR-007 predicted — because the discipline was
kept for six chapters with nothing verifying it.

> **Primer — [Docker: images, layers, and multi-stage builds](../technology/docker/images-and-layers.md)**
> Layer caching and instruction order, multi-stage builds, base-image choice, build context and
> `.dockerignore`, exec vs. shell entrypoint and signal handling, layered JARs, `ARG` vs `ENV`.

---

## The backend Dockerfile

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY services services
RUN mvn -q -pl services/common,services/order-service -am package -DskipTests
RUN rm -f services/order-service/target/*.jar.original

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/services/order-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Multi-stage: Maven and the JDK build; a JRE runs. The final image contains one jar and no build
tooling, no compiler, and no source.

### The build-context comment is the interesting part

```dockerfile
# Build context is the repo root (see docker-compose.yml: context: .) because Maven's reactor
# requires every module listed in the root pom.xml's <modules> to exist on disk even when -pl
# restricts which ones actually get built (verified empirically — `mvn -pl A,B -am` still fails
# fast with "Child module ... does not exist" if a sibling module directory is absent). So the
# build stage copies the whole services/ tree, then -pl/-am compiles only common + this service.
```

The obvious approach — context the service directory, copy only what that service needs — does not
work, and the comment says exactly why *and* that it was **verified empirically** rather than assumed.

`-pl services/common,services/order-service` restricts what is *built*; `-am` ("also make") builds
required dependencies. But the reactor still parses the root POM and insists every listed module
exists on disk.

**The cost, stated honestly:** copying the whole `services/` tree means a change in *any* service
invalidates the `COPY services services` layer in *every* service's image. Full rebuilds all round.
Acceptable at five services; the fix at fifty would be per-module POM copies before source, or a
purpose-built build image.

### `.jar.original`

```dockerfile
RUN rm -f services/order-service/target/*.jar.original
```

`spring-boot-maven-plugin` repackages the plain jar into an executable one and leaves the original
beside it as `*.jar.original`. The `COPY --from=build … target/*.jar` glob would match both, and
copying two files to one destination fails.

A small, real papercut of the kind that only shows up when you try.

---

## The frontend Dockerfile, and a genuine asymmetry

```dockerfile
FROM node:22-alpine AS build
ARG VITE_ORDER_SERVICE_URL=http://localhost:8081
ARG VITE_INVENTORY_SERVICE_URL=http://localhost:8082
# …
ENV VITE_ORDER_SERVICE_URL=$VITE_ORDER_SERVICE_URL
# …
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
```

Dependencies before source, so `npm ci` stays cached. Node builds; nginx serves. The runtime image
contains static files and a web server.

The header comment names the asymmetry that makes frontends different:

> The frontend is a browser-side SPA: `fetch()` calls made in the user's browser hit backend URLs
> **baked in at build time**.

A backend reads its configuration at startup, so one image serves every environment. **A browser-side
SPA has its configuration compiled into the bundle**, so the environment is chosen at build time —
hence `ARG` rather than `ENV`, and one image per environment.

The two builds:

> - **Local Compose / kind** (the default): the ARGs default to `http://localhost:808X`, which stays
>   correct because `docker-compose.yml` publishes every backend's port to the host, and
>   `kind-config.yaml` maps those same host ports for kind — either way the browser talks to the host.
> - **Production**: pass each ARG as a relative same-origin prefix, e.g.
>   `--build-arg VITE_ORDER_SERVICE_URL=/svc/order`. `apiFetch` and both `EventSource` URLs concatenate
>   `${baseUrl}${path}` unchanged either way, **so a relative prefix works with no other code change.**

That last clause is the payoff for a decision made in
[Chapter 2](../02-domain/6-the-first-frontend.md): base URLs as configuration, concatenated rather than
constructed. It costs nothing then and it is what makes
[Chapter 9](../09-production/README.md)'s single-hostname deployment a build argument rather than a
refactor.

### The nginx config exists for one line

> `nginx.conf` adds the SPA `try_files` fallback the stock `nginx:alpine` image doesn't have (a deep
> link or refresh on a client-side route 404s without it — see `App.tsx`'s `BrowserRouter`).

A client-side router owns paths the server knows nothing about. Request `/scenarios` directly — a deep
link, or a refresh — and nginx looks for a file called `scenarios`, finds none, and returns 404. The
fallback serves `index.html` for anything that is not a real file, and the router takes over.

The direct consequence of the deep-linkability requirement that motivated React Router in
[Chapter 5](../05-scenarios-and-frontend/5-the-console.md). Adopt a client-side router, own a server
rewrite rule.

---

## Compose

Ten services: PostgreSQL, Kafka, five backends, the frontend, Prometheus, and Grafana.

### The Kafka listener split

The longest comment in the file, and worth reading in full because it explains a failure that is
genuinely baffling the first time:

```yaml
KAFKA_LISTENERS: INTERNAL://0.0.0.0:29092,HOST://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,HOST://localhost:9092
```

> **HOST** is what host-side tools (local `mvn spring-boot:run`, Testcontainers-based integration
> tests, `kafka-console-consumer` from your laptop) connect to via `localhost:9092`. **INTERNAL** is
> what containers on the compose network use via `kafka:29092` — a name that only resolves inside the
> compose network, which is the point: a container that connects on INTERNAL gets handed back the
> INTERNAL advertised address for subsequent produce/consume, instead of "localhost" resolving to
> itself. Without this split, a container-side client would connect fine on its first request (to the
> bootstrap address) and **then fail as soon as it tried to actually produce/consume**, because the
> broker's metadata response would tell it to reconnect to "localhost" — itself, not the broker.

The mechanism worth internalizing: **a Kafka client's bootstrap address is only used to fetch
metadata.** The broker then replies with its *advertised* addresses, and the client reconnects to
those for all real work.

So a single advertised address cannot serve two networks with different names for the same broker.
Connection succeeds, produce fails, and the error points at `localhost` — which, inside a container,
is the container itself.

This is the most common Kafka-in-Docker problem there is, and the answer is always two listeners.

### Health checks and ordering

```yaml
depends_on:
  postgres:
    condition: service_healthy
  kafka:
    condition: service_healthy
```

`depends_on` alone only orders *starts*. `condition: service_healthy` waits for the dependency's own
health check to pass — the difference between "Postgres has been started" and "Postgres will accept a
connection."

And the Kafka check is deliberately a real round trip:

> Broker API versions round-trip proves the broker is **actually accepting client connections** on the
> HOST listener, not just that the process is up (KRaft startup has a window where the process is
> running but not yet serving).

> **We got this wrong — later, and elsewhere.** This exact check, `kafka-broker-api-versions.sh`,
> **starts a JVM per invocation**. Harmless on a laptop every 5 seconds; on a 2-vCPU production box it
> was identified as the thing that flaps the broker under CPU contention, and
> [Chapter 9](../09-production/README.md) replaces it with a TCP socket check. A correct check whose
> *cost* was wrong for a different environment.

The backend health checks use `wget --spider` against `/actuator/health` — `wget` because
`nginx:alpine` and the Temurin images have it and not `curl`, and `--spider` because a HEAD-like
request is enough.

### One command

```bash
docker compose up --build
```

Ten containers, ordered by health, on one network, with the frontend on `localhost:5173` and each
backend on its own port. That is Phase 7's exit criterion satisfied — and it is what makes every later
chapter's "just run it" instruction honest.

---

[← Chapter 7](README.md) · [Next: Kubernetes manifests →](2-kubernetes-manifests.md)
