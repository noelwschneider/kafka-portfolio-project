# Docker: images, layers, and multi-stage builds

*Referenced from [Chapter 7.1 — Containers and Compose](../../07-containers-and-kubernetes/1-containers-and-compose.md).*

---

## Images are stacks of layers

Each instruction in a Dockerfile that changes the filesystem (`COPY`, `RUN`, `ADD`) produces a
**layer** — a diff against the layer below. An image is those layers stacked; a container is the image
plus a thin writable layer on top.

Two consequences drive almost every Dockerfile decision:

**Layers are cached, keyed by the instruction and its inputs.** If nothing has changed, the layer is
reused. If something has, that layer **and every layer after it** are rebuilt.

**Layers are immutable.** Deleting a file in a later layer does not shrink the image — the file is
still in the earlier layer, just masked. A secret `COPY`d in and `RUN rm`'d out later is still in the
image, and still extractable.

## Ordering for cache hits

Put what changes rarely before what changes often.

```dockerfile
COPY package.json package-lock.json ./
RUN npm ci            # ← cached unless dependencies changed
COPY . .
RUN npm run build     # ← reruns on any source change
```

Copying the whole tree first would invalidate the dependency install on every source edit, turning a
five-second rebuild into a two-minute one. The same shape works for Maven (`pom.xml` first), Go
(`go.mod`), Python (`requirements.txt`), and Rust (`Cargo.toml`).

## Multi-stage builds

The tools that build your application are not the tools that run it. A JDK plus Maven plus a local
repository is several hundred megabytes; a JRE plus one jar is a fraction of that.

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY services services
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Only the final stage becomes the image. Everything in the build stage — the compiler, the package
manager, the source, the dependency cache — is discarded.

Three benefits, and the third is the one people forget:

- **Size.** Typically an order of magnitude.
- **Speed.** Less to push and pull, on every deploy.
- **Attack surface.** No compiler, no package manager, no build tooling, and **no source code** in the
  running image. Nothing an attacker can use to build something new in place.

## Choosing a base image

| Base | Size | Notes |
|---|---|---|
| `eclipse-temurin:21` (Debian) | ~450MB | Full toolchain available; easiest to debug |
| `eclipse-temurin:21-jre` | ~270MB | Runtime only |
| `eclipse-temurin:21-jre-alpine` | ~180MB | musl libc, not glibc — occasionally matters for native libraries |
| `gcr.io/distroless/java21` | ~190MB | No shell at all: nothing to `exec` into, and nothing an attacker can either |

**Alpine's musl libc** is the one real gotcha. Most JVM workloads are fine; anything with native
dependencies (some image or crypto libraries) may not be. Test before assuming.

**Distroless** is the strongest option and the most annoying to debug — no shell means no
`kubectl exec` into a running pod. Fine for a service you never poke at; painful for one you do.

## Build context

```
docker build -f services/order-service/Dockerfile .
```

The final `.` is the **build context** — the directory sent to the Docker daemon before the build
starts. Everything in it is transferred, even files no instruction ever copies.

A `.dockerignore` is therefore not optional on a real repository: without it, `node_modules`, `target`,
`.git`, and every build artifact are packed up and shipped for every build.

Context also constrains what you *can* copy: `COPY` cannot reach outside it. For a monorepo where one
service's build needs a sibling module, that usually means the context is the repository root and the
Dockerfile lives in the service directory.

## Entrypoint and signals

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]      # exec form — correct
ENTRYPOINT java -jar app.jar                # shell form — avoid
```

The **exec form** (a JSON array) runs the process directly as PID 1, so it receives `SIGTERM` when the
container is stopped and can shut down gracefully.

The **shell form** wraps the command in `/bin/sh -c`, so the shell is PID 1 and typically does not
forward signals. The result is that every stop waits for the full grace period and then kills the
process — no graceful shutdown, no connection draining, and a puzzling ten-second delay on every
deploy.

## Layered JARs, for Spring Boot specifically

A Spring Boot fat jar is one file, so any code change invalidates the whole ~50MB layer even though
the dependencies inside it did not move.

Spring Boot's layertools splits it:

```dockerfile
FROM eclipse-temurin:21-jre-alpine AS extract
COPY target/app.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
COPY --from=extract dependencies/ ./
COPY --from=extract spring-boot-loader/ ./
COPY --from=extract snapshot-dependencies/ ./
COPY --from=extract application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

Dependencies become their own layer, cached across builds; only the small application layer changes.
Worth it when you push images often, and unnecessary when you do not.

## Build-time vs runtime configuration

```dockerfile
ARG VITE_API_URL=http://localhost:8081     # build time only
ENV SPRING_PROFILES_ACTIVE=production      # available at runtime
```

`ARG` exists only during the build. `ENV` persists into the running container and can be overridden at
`docker run`.

The distinction matters most for **frontends**. A browser-side SPA has its configuration compiled into
the bundle, so it must be an `ARG` — and that means one image per environment. A server-side
application reads its configuration at startup, so it can be an `ENV` and one image serves everywhere.

That asymmetry is not a flaw in the tooling; it is a consequence of where the code runs. The usual way
around it for an SPA is to serve configuration from an endpoint the bundle fetches at startup, at the
cost of an extra round trip before the app can render.

**Never bake a secret in with either.** `ARG` values are visible in the image history; `ENV` values are
visible to anyone who can inspect the image.
