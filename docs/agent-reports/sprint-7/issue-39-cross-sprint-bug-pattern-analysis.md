# Issue #39 — cross-sprint bug pattern analysis

Retrospective synthesis over six written reports (Sprint 4's #25, Sprint 5's #27/#27-followup/#27-verification/#28/#29,
Sprint 6's #36/#36-verification), looking for shared failure mechanisms rather than surface similarity
("they're all Kafka bugs"). Reading and synthesis only — nothing reproduced live, nothing fixed. Issue
#41 (the sibling Sprint 7 task diagnosing the `ConsumerErrorHandlerFactory` MAX_RETRIES bug) had not
landed a report at the time of this pass; one pattern below (#3) is built from the sprint-6 verification
report's own incidental discovery of that bug plus my own reading of the shared classifier's current
code, not from #41's own diagnosis, which should be treated as authoritative over my inference once it
lands.

## Patterns found

### Pattern 1 — dedup/identity keys built from ephemeral physical coordinates instead of a stable application identifier, where the physical coordinate space can be reset independently of the data compared against it

**Supported by:** #25 (sprint 4), #27 + its two follow-ups (sprint 5). **Contrasting control, same
mechanism confirmed absent:** #29 (sprint 5).

`EventProjectionConsumer.project()` deduped on `(topic, partition, offset)` — coordinates that only
identify a record uniquely within one continuous Kafka broker log. `docker-compose.yml`'s `kafka`
service had no persistent volume while `postgres` did, so every stack rebuild reset every topic to
offset 0 while the previously-projected rows survived in Postgres untouched. The first new record
after a reset landed on the same coordinates as a pre-existing stale row and was silently treated as an
already-seen redelivery — a false-positive "already have this" from a key that was never designed to
survive the reset. #27 fixed this with a composite `(topic, partition, offset, event_id)` key, keeping
`event_id` (a UUID, stable across resets) as the tiebreaker.

The fix effort itself re-demonstrated the mechanism twice more, on the infra side: the volume mount path
chosen in #27's first pass (`/tmp/kraft-combined-logs`) was based on the image's shipped **default
config template**, not the container's actual effective config once environment variables drive it —
`KAFKA_LOG_DIRS` was never set, so Kafka fell back to a different, unmounted default (`/tmp/kafka-logs`),
and the volume never actually persisted anything. This was caught by an independent verifier (`issue-27-verification.md`),
not by the original implementer, and required a second follow-up fix (`issue-27-followup-kafka-volume-path.md`)
that also had to work around a second, independent problem — a fresh named volume under `/tmp` has no
ownership to inherit and isn't writable by the container's non-root user — before landing on
`/var/lib/kafka/data`, the path the image itself declares as a `VOLUME` and pre-`chown`s at build time.

**The contrasting control matters as much as the bug.** #29 confirms the four domain services'
idempotency ledgers (`services/common`'s `ProcessedEventKey`) do *not* have this flaw — they key on
`eventId` end-to-end, generated once at outbox-insert time and never regenerated on retry, confirmed by
reading all seven domain `@KafkaListener` classes and the outbox insert/dispatch path. The domain
services got this right specifically because they route through one shared, purpose-built type; the
scenario-service projection got it wrong because it implemented its own bespoke
`existsByTopicAndPartitionAndOffset` check outside that shared module, for a locally-reasonable purpose
(dedup against genuine at-least-once redelivery within one broker's lifetime) that never considered
reset-invalidation.

**What a future bug hunt should specifically look for:**
- Grep every dedup/"already processed"/uniqueness check in the codebase for what it's actually keyed
  on, not what its Javadoc claims. Flag anything keyed on Kafka `(topic, partition, offset)`, an
  auto-incrementing counter, or any value whose stability depends on a log/process that can be wiped or
  restarted independently of the data being compared against it (`scenario_service.events` is now
  fixed; nothing else in these reports was found using this anti-pattern, but nothing outside
  `EventProjectionConsumer` and the four domain ledgers was audited for it either).
- Any future infra-config fix claiming "X now persists across a restart" should be verified by reading
  the *running container's actual effective config* (`docker exec ... cat` the real config file, not
  the image's shipped template) and by actually cycling the restart and re-checking a durable marker
  (TopicId, offset, row count) — not by inspecting the compose YAML and the image's documentation
  in isolation. Two of #27's three landed changes needed a second pass specifically because this step
  was skipped the first time.

### Pattern 2 — async-completion races between independently-progressing consumers, where cleanup fires on one subsystem's terminal signal while a second subsystem can still need to write against the state being cleaned up

**Supported by:** #36 (sprint 6), both its primary bug and a second bug it found using the same
mechanism.

`RunRegistry.finish()` removed the `correlationId -> runId` mapping the instant `ScenarioRunExecutor`
observed its driving signal — Order Service's own SSE stream reaching a terminal order status. But
`EventProjectionConsumer` is a *separate* Kafka consumer group (`scenario-service-projection`),
progressing on its own schedule against the same underlying event stream. Nothing coordinates "the order
is terminal" with "the projection consumer group has drained every record for this run" — they are two
independently-clocked observers of the same saga. A record consumed after the mapping was removed had no
run to attach an EVENT-kind timeline entry to, even though it was durably projected (Pattern 1's fix,
untouched). The report confirmed this is structural, not specific to `DuplicateEventScenario`'s
fire-and-forget republish (the originally-suspected trigger) — every scenario that completes via
`awaitTerminal()` races the same way, and `PoisonMessageScenario`'s fixed-sleep wait for its own DLQ
record was explicitly named as sharing the identical shape, unverified.

The fix (defer both `retireCorrelation()` and `timelineRecorder.forget()` by a grace period) is honestly
characterized in its own report as a wider window, not a barrier — the underlying question of which of
several async observers gets to decide a run's bookkeeping is retired was never resolved architecturally,
just made much less likely to bite at demo-scale traffic.

**The second bug this same investigation found is the same mechanism at a smaller scale, one level
down:** `TimelineRecorder.forget(runId)` cleared an **in-memory** per-run sequence counter tied to a
**durable** DB unique constraint (`UNIQUE(run_id, sequence)`). If a late write arrived after the
in-memory counter was forgotten, `computeIfAbsent` would restart the counter at 1 and collide with rows
already committed under sequence 1..N. This is Pattern 1's shape (an ephemeral, resettable piece of
state trusted to stay in sync with a durable one across a lifecycle event that only resets the ephemeral
half) arising inside a Pattern 2 race — the two patterns compound rather than being unrelated.

**What a future bug hunt should specifically look for:**
- Every place a scenario/saga/run-lifecycle construct tears down or resets bookkeeping (registries,
  per-run counters, SSE subscriptions, in-memory caches) on a "this run is done" signal — enumerate
  every *other* subsystem that can still write against that same run/aggregate after that signal fires,
  the way #36's own blast-radius section did for the eight scenario runners. `PoisonMessageScenario`'s
  DLQ-arrival race is the one already-named, already-identified, still-unverified instance — a
  concrete starting point, not a new finding of this report.
- `DemoResetService`'s concurrent-reset race (named as deferred backlog in `docs/planning/sprint-7/sprint-7-plan.md`,
  not analyzed in any report read for this pass) is worth checking against this exact question — does a
  reset clear state that an in-flight consumer can still write to — before assuming it's a different
  mechanism. I have not read `DemoResetService`'s code and cannot confirm this; it's a hypothesis to
  test, not a finding.
- Sprint 6's own commit history (`8b05059 sprint 6 review: require verifier delegation for concurrency
  and contract work`) shows the project already responded structurally to this pattern once, by
  requiring independent verification for concurrency work — the #36 primary fix was accepted from the
  implementer's own report and only caught its second bug (the sequence-counter collision, which
  directly produced #41) because of a later, deliberately-skeptical verification pass. That process
  fix is doing real work; a future bug hunt should keep leaning on it rather than treating one clean
  implementer report as sufficient for anything touching two independently-progressing consumers.

### Pattern 3 — the shared Kafka error-classifier assumes uniform exception translation, but Spring only translates exceptions raised at a repository-method-call boundary, not ones surfacing at deferred-flush/commit time

**Supported by:** the sprint-6 #36-verification report's incidental discovery, which is the origin of
Sprint 7's own #41.

While reproducing an unrelated counterfactual (an un-deferred `timelineRecorder.forget()`), the verifier
directly observed a genuine Postgres unique-constraint violation (`23505`) inside a `@Transactional`
Kafka-listener method get retried well past the configured `MAX_RETRIES=3` budget (more than 4 total
delivery attempts before it happened to land on an unused sequence number and stop). `NonTransientDataAccessException`
is explicitly listed as non-retryable in `ConsumerErrorHandlerFactory.NON_RETRYABLE`
(`services/common/src/main/java/com/orderfulfillment/common/kafka/ConsumerErrorHandlerFactory.java:78-82`),
and the class's own Javadoc names constraint violations as exactly the case that list is for. The
violation nonetheless evaded classification.

Reading `ConsumerErrorHandlerFactory.isRetryable()` directly
(`ConsumerErrorHandlerFactory.java:156-165`) shows it walks a `Throwable` cause chain via `getCause()`
and checks `isInstance` against a fixed list. That is correct for exceptions Spring's
`PersistenceExceptionTranslationInterceptor` translates when a `@Repository` method call itself throws.
It is a plausible, untested-by-this-report hypothesis that it is *not* correct for a write JPA defers to
flush time: `EventRecordRepository.save()`/`TimelineRecorder.append()`'s actual `INSERT` reaches
Postgres inside the transaction manager's own commit path, not inside the repository method call, and a
failure surfacing there is caught and re-wrapped by `JpaTransactionManager`/`TransactionInterceptor`
machinery (typically `TransactionSystemException`, which does not extend `NonTransientDataAccessException`)
rather than by the same interceptor that translates inline repository-call exceptions. I have not
re-reproduced this or read #41's own diagnosis (not yet landed) to confirm this specific mechanism —
flagging it as the most likely explanation given the static code and the one reproduced case, to be
checked against #41's actual findings, not asserted as fact.

**What a future bug hunt should specifically look for**, once #41's own report lands and either
confirms or corrects the mechanism above:
- Audit every other Kafka listener across all four domain services plus scenario-service for the same
  shape: a `@Transactional` listener method whose persistence write is not flushed inline (no explicit
  `flush()`/`saveAndFlush()` before the method returns) and that relies on `ConsumerErrorHandlerFactory`'s
  shared classification to route constraint violations to non-retryable. Per the class's own Javadoc,
  this factory is used by "every service in this project" — the escape is not scenario-service-specific,
  it's a property of the shared classifier meeting any listener with this write pattern, exactly as
  Sprint 7's own plan already frames it ("could affect any Kafka listener across any service that hits a
  DB constraint violation").
- More generally: audit every other shared-infrastructure exception classifier or retry/non-retry
  decision point in `services/common` for the same repository-call-time-vs-commit-time assumption, since
  this is the second time in this set of reports (after Pattern 1) that a piece of shared infrastructure
  turned out to encode an assumption that was true for the case it was written against but not for every
  case it now gets used for.

### Pattern 4 — a compensating action exists for the failure path but no symmetric action exists for the success path

**Supported by:** #28 (sprint 5). Named as its own pattern rather than folded into 1–3 because the
mechanism is genuinely different — this is a structural design gap, not a race or an identity-key bug.

Inventory Service has exactly two `@KafkaListener` beans: one reacts to `OrderCreated` (reserve stock),
the other to `PaymentRejected` (release the reservation). No consumer exists for any fulfillment-side
event (`ShipmentCreated`), so `reserved_quantity` is only ever decremented by the failure/compensation
path. This matches the frozen event contract (`ShipmentCreated` is documented as consumed by Order
Service only) and was already named and deliberately deferred in Sprint 2
(`docs/agent-reports/sprint-2/deployment-execution-report.md` §6, "Option B") — #28 independently
reproduced and confirmed the same mechanism without the connection to Sprint 2 having been made at the
time. The report's own recommendation is that this needs a real design pass (new `ReservationStatus`
value, a real "consumed by success" event/reason, possibly a decrement to `available_quantity` too), not
a narrow wiring fix.

**What a future bug hunt should specifically look for:** every saga/compensation pair in the system —
anywhere a failure path has an explicit "undo" or "release" action — should be checked for whether the
corresponding success path has a symmetric consuming action, or whether (as here) success silently
leaves the reservation/hold in place forever. This is a one-instance finding in the reports read for
this pass; I have not checked whether Order, Payment, or Fulfillment Service have an analogous pattern
of their own (#28's own report explicitly scoped that question out too — see its Deliberately not
covered section).

## Cross-cutting observation: every defect here lived inside a branch that was deliberately designed to be a quiet, expected no-op

This is worth naming on its own because it explains *why* four defects with different mechanisms all
went unnoticed through ordinary use rather than being caught by routine testing or operation:

- #25: the dedup skip that silently dropped genuinely-new events logs at `log.debug`, and
  `application.yml` runs at `INFO` — a deliberate "this is a normal redelivery, don't clutter logs"
  choice. The report found this had been silently dropping data for at least four days
  (`order-20000`, dated 2026-08-21) before anyone queried the table directly.
- #28: `reserved_quantity` staying nonzero after `FULFILLED` produces no error and never violates its
  own `CHECK (reserved_quantity <= available_quantity)` constraint — a silently-accumulating value with
  a passing invariant, not a crash or a failed assertion anywhere.
- #36: the missing timeline entry is not an error either — `GET /demo/scenario-runs/{runId}` returns
  200 with a shorter-than-expected but perfectly well-formed timeline. Nothing about the response shape
  signals a record is missing.
- #41's precursor (the one case in this set that *did* make some noise, though still easy to miss): five
  repeated `23505` stack traces logged at `ERROR`, which self-resolved once a retry happened to land on
  a free sequence number — no counter incremented, no test asserting on retry count, no alert. Noisy in
  the log but silent operationally.

Every one of the four mechanisms above required an agent to deliberately, skeptically re-derive ground
truth from the database or a live reproduction rather than trusting an absence of errors — which is
exactly what each of these reports' own "reproduce before you explain" sections did. A future bug hunt
gets a concrete, cheap starting heuristic out of this: **grep every consumer/idempotency/lifecycle-cleanup
code path for `log.debug` (or any branch with no `WARN`/`ERROR`/metric) guarding a decision to skip,
drop, or no-op** — this is where all four of the defects above lived, and it is a search that costs
minutes and has already paid off four times without anyone framing it as a deliberate search strategy
until now.

## Named backlog items this analysis can sharpen (not schedule)

`docs/planning/sprint-7/sprint-7-plan.md`'s "Explicitly not in scope" section already names several bug
hunt candidates with no report behind them yet. Mapping them to the patterns above is a hypothesis for
where to start looking, not a diagnosis — I did not read any of this code for this pass:

- **`PoisonMessageScenario` DLQ verification** — already explicitly named as sharing Pattern 2's shape
  in #36's own report. Highest-confidence match of the group.
- **`DemoResetService` concurrent reset race** — plausible Pattern 2 candidate (a reset clearing state an
  in-flight consumer still writes to) but unconfirmed; could equally be an ordinary check-then-act race
  with no relation to the async-observer shape.
- **Kafka consumer rebalance mid-transaction** — plausible Pattern 1 or Pattern 2 candidate depending on
  what specifically goes wrong (identity/offset assumptions vs. two consumers disagreeing on state); the
  name alone doesn't disambiguate which.
- **`HttpMediaTypeNotSupportedException`** — no evident connection to any of the four patterns above from
  the name alone; likely an unrelated API-contract/content-negotiation issue, not a candidate for
  "audit the same mechanism elsewhere."
- **Inventory Option B (release reservations on `FULFILLED`)** — this is Pattern 4's own fix, already
  fully scoped by #28's report; not a new pattern instance, just the deferred remediation of the one
  already found.

## What changed

Nothing in source or existing docs. One new file:

- `docs/agent-reports/sprint-7/issue-39-cross-sprint-bug-pattern-analysis.md` (this report).

## How this was verified

This is a synthesis task over existing written reports, not a live reproduction — per the task's explicit
scope, nothing was reproduced and nothing was fixed. "Verification" here means: read every source report
in full (not summarized secondhand), and spot-checked the two claims load-bearing enough to state as
fact in Pattern 3 (which no source report fully diagnoses — #41 hadn't landed) against the actual current
code, rather than inferring them purely from the sprint-6 verification report's prose.

Confirmed #41 has not landed a report yet, so Pattern 3 is built from the sprint-6 verification report's
incidental discovery plus my own code reading, not from #41's own diagnosis:

```
$ ls /Users/noel/Documents/HelloWorld/kafka-portfolio-project/docs/agent-reports/sprint-7/ 2>/dev/null
(no output — directory does not exist yet)
```

Confirmed the shared classifier's current structure directly, rather than trusting the verification
report's paraphrase of it:

```
$ sed -n '78,82p;156,165p' services/common/src/main/java/com/orderfulfillment/common/kafka/ConsumerErrorHandlerFactory.java
    private static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
            UnsupportedEventVersionException.class,
            JacksonException.class,
            NonTransientDataAccessException.class,
            IllegalArgumentException.class);
...
    public static boolean isRetryable(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (Class<? extends Exception> nonRetryable : NON_RETRYABLE) {
                if (nonRetryable.isInstance(t)) {
                    return false;
                }
            }
        }
        return true;
    }
```

Confirmed `NonTransientDataAccessException` is genuinely listed (ruling out "the verifier misread the
list"), and confirmed the classifier's own class-level Javadoc references a different, unrelated
prior gap (`docs/reliability-pattern.md` "Gap 1" — optimistic-locking retries, not constraint-violation
classification) so as not to conflate the two:

```
$ grep -n -A15 "Gap 1" docs/reliability-pattern.md | head -20
## 5. Gap 1 — retry exhaustion now has a defined destination

This was left open: `InventoryReservationFailed.reason` is frozen to
`INSUFFICIENT_STOCK | UNKNOWN_SKU`, and neither is
true when a reservation loses 25 optimistic-lock races in a row. ...
**Resolved by routing, with no contract change.** `ObjectOptimisticLockingFailureException` is a
`TransientDataAccessException` and so classifies retryable (§3.2). ...
```

Confirmed #29's contrasting-control claim (domain ledgers key on `eventId`, not physical coordinates) by
reading the actual shared type, not just the report's paraphrase:

```
$ sed -n '1,26p' services/common/src/main/java/com/orderfulfillment/common/idempotency/ProcessedEventKey.java
...
public record ProcessedEventKey(UUID eventId, String consumerName) {
    public ProcessedEventKey {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(consumerName, "consumerName");
    }
}
```

No `topic`/`partition`/`offset` field — matches #29's claim exactly.

Confirmed the sprint-7 plan's own framing of #41 and the "explicitly not in scope" backlog list (used in
the "Named backlog items" section above) against the actual current planning doc, not memory of it:

```
$ sed -n '11,21p;74,82p' docs/planning/sprint-7/sprint-7-plan.md
1. [#41] `ConsumerErrorHandlerFactory`: constraint violations retried past MAX_RETRIES budget —
   surfaced during Sprint 6 review's independent verification of #36. ...
   This is shared infrastructure, not specific to #36's fix: it could affect any Kafka listener
   across any service that hits a DB constraint violation ...
...
**Bug hunt follow-ups** (`HttpMediaTypeNotSupportedException`, Kafka consumer rebalance
mid-transaction, `DemoResetService` concurrent reset race), the **#36 regression test** and
**PoisonMessageScenario DLQ verification**, and **Inventory: release reservations on FULFILLED
(Option B)** — all require actual reproduction and likely code fixes ...
```

## Judgment calls

- **Treated #29 as a control/negative result worth reporting, not just skipped it as "no bug found."**
  The task asked for shared shapes across "the bugs" — #29 isn't a bug, it's an audit that confirmed the
  absence of one. I kept it because the contrast (why the domain ledgers got the identity-key question
  right when the projection consumer got it wrong) is itself part of Pattern 1's mechanism, not
  incidental — it points at "route dedup logic through the shared module" as the concrete preventive
  measure, which a pure list of failures wouldn't surface.
- **Named #28 as its own, fourth pattern rather than forcing it into 1–3.** The task explicitly warned
  against surface-level grouping ("not surface similarity... actual mechanism"); #28's mechanism
  (asymmetric compensation) genuinely doesn't share a root cause with the other three, and inventing a
  false shared mechanism to get to a tidier "three patterns, symmetric with the three examples given"
  would have been worse than reporting four with one flagged as singleton-so-far.
- **Read `ConsumerErrorHandlerFactory.java` directly and formed my own hypothesis about the commit-time
  translation gap, rather than only restating the sprint-6 verifier's observation.** The verifier
  explicitly declined to root-cause this ("chasing it further ... is root-causing a bug in the reverted,
  temporary code path, not verifying the shipped fix"). Since #41 hadn't landed, I read the classifier
  myself to give the future bug-hunt something more actionable than "something about wrapping is wrong."
  I flagged this explicitly as *my* inference, not a re-statement of a diagnosed root cause, and said so
  twice in the pattern text, because #41's own report should supersede it and I have not verified it
  against a live reproduction myself — doing so would have crossed into live investigation, out of this
  task's explicit scope.
- **Did not go looking for the code behind the sprint-7-plan.md's other named backlog bugs**
  (`DemoResetService`, `HttpMediaTypeNotSupportedException`, Kafka rebalance mid-transaction) even
  though they're mentioned in a source document I read. No report describes their mechanism, so mapping
  them to patterns 1–4 is explicitly labeled as a hypothesis in the "Named backlog items" section, not
  a finding — reading their implementation to firm that up would be live investigation, out of scope
  for a task defined as "reading and synthesis... over existing written reports."
- **Did not read `docs/agent-reports/sprint-2/deployment-execution-report.md` §6 in full**, only as
  quoted inside #28's own report — #28 already did the work of establishing that Sprint 2 named the
  same mechanism, and re-reading the sprint-2 source directly would not have changed anything in this
  pass's synthesis, only re-confirmed a citation #28 already made carefully with direct quotes.

## Deliberately not covered

- **Issue #41's own diagnosis** — not read, because it had not landed a report at the time of this pass,
  per the task's own instruction not to wait for it. Pattern 3 above is built from the sprint-6
  verification report's incidental discovery plus my own reading of the current shared classifier code,
  and should be checked against #41's actual findings once available — it may confirm, sharpen, or
  correct the commit-time-translation mechanism I inferred.
- **No live reproduction of anything** — explicitly out of scope per the task ("do not reproduce any of
  the historical bugs live"). Every mechanism claim above traces to either a source report's own direct
  reproduction (cited by report name throughout) or, for Pattern 3's mechanism specifically, my own
  static reading of current code, clearly flagged as inference rather than confirmed fact.
- **No new sprint scope proposed** — per the task's explicit boundary, this report names what a future
  bug hunt should look for (grep targets, audit questions, specific files/classes to start from) but
  does not schedule or size that work; that is the developer's and a future `/sprint-plan`'s call.
- **Did not audit `docs/agent-reports/sprint-1` through `sprint-3`** for earlier instances of these same
  patterns — the task named a specific set of source reports (sprint-4 through sprint-6) and I stayed
  within it. #28's own report already surfaces one sprint-2 connection (the `deployment-execution-report.md`
  §6 citation) without me needing to independently trawl earlier sprints for more.
- **Did not check whether Order, Payment, or Fulfillment Service have a Pattern-4-shaped asymmetric
  compensation gap of their own** — #28's own report explicitly scoped that question out for Inventory
  Service specifically, and I did not extend the check to the other three services; named as an open
  question in Pattern 4 above, not answered.
- **Did not verify `PoisonMessageScenario`'s DLQ-path race independently** — both #36's original report
  and its verification explicitly left it unreproduced; I did not add a reproduction of my own, since
  doing so would be live investigation into a historical-adjacent bug rather than synthesis, and it's
  already correctly named as inventory in two prior reports.
