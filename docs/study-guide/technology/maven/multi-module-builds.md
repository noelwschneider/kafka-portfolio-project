# Maven multi-module builds

*Referenced from [Chapter 2.1 — The project skeleton](../../02-domain/1-project-skeleton.md).*

---

## The problem

One repository, several independently deployable applications, and real code shared between them.
Copying the shared code produces divergent copies within a month; a separate repository for it means
version-bumping and re-releasing for every change.

## The aggregator POM

A `pom.xml` with `<packaging>pom</packaging>` builds no artifact of its own. It lists modules:

```xml
<groupId>com.orderfulfillment</groupId>
<artifactId>order-fulfillment-systems-lab</artifactId>
<version>0.1.0</version>
<packaging>pom</packaging>

<modules>
    <module>services/common</module>
    <module>services/order-service</module>
    <module>services/inventory-service</module>
</modules>
```

`mvn install` at the root builds every module **in dependency order** — Maven computes the order from
inter-module dependencies, not from the listing order. Running it inside one module's directory builds
only that module, resolving its siblings from your local repository.

A module depends on a sibling by ordinary coordinates:

```xml
<dependency>
    <groupId>com.orderfulfillment</groupId>
    <artifactId>common</artifactId>
    <version>${project.version}</version>
</dependency>
```

`${project.version}` is inherited from the parent, so there is one version number for the whole tree
and no per-module bumping.

## Two distinct jobs, often confused

A parent POM does two things that are worth keeping separate in your head:

**`<modules>` — aggregation.** What gets built together.

**`<dependencyManagement>` — version alignment.** *If* a child declares this dependency, use this
version. It does **not** add the dependency. A child still declares what it uses; it just omits the
`<version>`.

These are independent. A POM can aggregate without managing versions, or manage versions for projects
it does not aggregate (which is what a published BOM is).

## Inheriting from `spring-boot-starter-parent`

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
</parent>
```

`spring-boot-starter-parent` carries a `dependencyManagement` section pinning consistent versions for
hundreds of libraries that Spring's own compatibility testing has exercised together.

This is why declaring `spring-boot-starter-web` **without a `<version>`** is correct rather than
sloppy — omitting it is how you opt into the tested set. Pinning your own version there is how you
opt out of it, usually without meaning to.

`<relativePath/>` (deliberately empty) tells Maven not to look for the parent on disk and to resolve
it from the repository instead. Your own child modules do the opposite:

```xml
<relativePath>../../pom.xml</relativePath>
```

## BOMs

A **Bill of Materials** is a POM that exists only to pin versions for a family of related artifacts.
Import one with `<scope>import</scope>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.21.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Now every `org.testcontainers:*` dependency in every module gets a consistent version from one place.

**A wrinkle worth knowing:** an imported BOM's entries do not always propagate reliably through more
than one parent hop across all tooling. The practical workaround is for each module to also declare
the dependency it uses directly (still without a version) — the import in the root still keeps the
versions from drifting, which is the point.

## Packaging: which modules produce runnable JARs

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

`spring-boot-maven-plugin` repackages the module's JAR into an executable fat JAR with a nested
classpath and a launcher.

**A library module must not have this plugin.** A Spring Boot fat JAR has a nonstandard internal
layout and cannot be depended on as an ordinary library. Application modules get the plugin; shared
modules do not.

## What belongs in a shared module

This is the decision that determines whether a shared module helps or quietly recreates the coupling
your service boundaries exist to prevent.

**In:** wire contracts expressed as code (message envelopes, payload types), and infrastructure with
no domain opinion (serialization, error models, correlation-ID plumbing, ID generation).

**Out:** anything domain-specific. If two services share a domain type, they are not really separate
services — a change to that type is a change to both, which is exactly the property a boundary is
supposed to remove.
