# Demo box idle memory investigation (89% at rest)

## What changed

Nothing. This was a read-only investigation against the live production demo box
(`kafka-demo-box` k3s context), performed via the existing SSH tunnel and `kubectl` only. No
resource limits, JVM flags, or workloads were touched, and no restart or `/deploy` was run.

## How this was verified

Tunnel and context were already up; confirmed before doing anything else:

```
$ ps aux | grep -i "ssh -f -N -L 16443"
noel  54884  0.0  0.0 ...  ssh -f -N -L 16443:127.0.0.1:6443 kafka-demo-box

$ kubectl config use-context kafka-demo-box && kubectl get pods -n orderfulfillment
Switched to context "kafka-demo-box".
NAME                                   READY   STATUS    RESTARTS       AGE
frontend-8f74446f8-qtr77               1/1     Running   4 (5d4h ago)   5d18h
fulfillment-service-54c84bc9fc-x2p96   1/1     Running   1 (5d4h ago)   5d4h
inventory-service-df5bb99d7-76r6h      1/1     Running   1 (5d4h ago)   5d4h
kafka-cbf7b9bd4-mfgx7                  1/1     Running   4 (5d4h ago)   5d18h
order-service-864bfb77bd-hfnhv         1/1     Running   1 (5d4h ago)   5d4h
payment-service-6448c9f75f-gpmxv       1/1     Running   1 (5d4h ago)   5d4h
postgres-894c4bc65-88zws               1/1     Running   4 (5d4h ago)   5d18h
scenario-service-6864445f88-swd2j      1/1     Running   1 (5d4h ago)   5d4h
```

**1. Node and per-pod memory.**

```
$ kubectl top nodes
NAME                       CPU(cores)   CPU(%)   MEMORY(bytes)   MEMORY(%)
kafka-portfolio-demo-box   189m         9%       3405Mi          89%

$ kubectl top pods -A --sort-by=memory
NAMESPACE          NAME                                      MEMORY(bytes)
orderfulfillment   kafka-cbf7b9bd4-mfgx7                     634Mi
orderfulfillment   order-service-864bfb77bd-hfnhv            363Mi
orderfulfillment   scenario-service-6864445f88-swd2j         363Mi
orderfulfillment   inventory-service-df5bb99d7-76r6h         358Mi
orderfulfillment   fulfillment-service-54c84bc9fc-x2p96      338Mi
orderfulfillment   payment-service-6448c9f75f-gpmxv          332Mi
orderfulfillment   postgres-894c4bc65-88zws                  111Mi
kube-system        traefik-59b7647586-p89gt                  38Mi
kube-system        metrics-server-6dc596dfb8-pz2vq           38Mi
kube-system        coredns-54996dc9b4-ptllp                  21Mi
kube-system        local-path-provisioner-58d557dc48-ll87q   13Mi
orderfulfillment   frontend-8f74446f8-qtr77                  3Mi
kube-system        svclb-traefik-bd746b02-pxrd5              0Mi
```

Sum of every pod on the box (all namespaces): 2612Mi. `kubectl top nodes` reports 3405-3422Mi
(three samples 20s apart: 3421Mi, 3422Mi, 3416Mi — flat). The gap, ~790-810Mi, is not attributable
to any pod; see Judgment calls for what it almost certainly is and why we can't confirm it directly.

**2. Comparison against `patch-tuning.yaml`'s design and the base manifests' limits/requests.**

```
$ kubectl describe node | grep -A6 "Allocated resources"
  Resource           Requests      Limits
  cpu                1350m (67%)   4250m (212%)
  memory             2572Mi (67%)  5034Mi (131%)

$ kubectl get node -o jsonpath='{.items[0].status.capacity}'
{"cpu":"2", ... ,"memory":"3911572Ki", ...}   # 3820Mi allocatable
```

Per-service limits from `infrastructure/kubernetes/0{4,5,6,7,8}-*-service.yaml`: all five backend
JVM services request 320Mi / limit 640Mi; Kafka requests 512Mi / limits 1Gi; Postgres requests
256Mi / limits 512Mi; frontend requests 64Mi / limits 128Mi.

Every pod is comfortably inside its own limit: order/scenario 363/640 (57%), inventory 358/640
(56%), fulfillment 338/640 (53%), payment 332/640 (52%), kafka 634/1024 (62%), postgres 111/512
(22%), frontend 3/128 (2%). None of the five JVM services show heap pressure — `MaxRAMPercentage=60`
against a 640Mi limit caps heap at ~384Mi, and actual usage (332-363Mi total RSS, heap+metaspace+
threads+buffers combined) sits well under that ceiling. This matches what T2 in `patch-tuning.yaml`
designed for.

**3. ADR-011 read in full** (see the ADR itself). It documents "normal" as: `maxSurge: 0` plus
`redeploy.sh`'s one-at-a-time restarts "held the fleet to its steady-state footprint through a full
five-service redeploy," verified live on 2026-08-21. It does not record a numeric idle-baseline
memory percentage for comparison — there is no "89% is normal" or "89% is new" statement to check
against directly.

**4. Restart/age history and OOMKilled check.**

```
$ kubectl get pods -A -o wide   # (trimmed to RESTARTS/AGE)
kube-system   coredns-...          4 (5d4h ago)    5d18h
kube-system   traefik-...          11 (5d4h ago)   5d18h
kube-system   metrics-server-...   5 (5d4h ago)    5d18h
orderfulfillment  frontend-...     4 (5d4h ago)    5d18h
orderfulfillment  kafka-...        4 (5d4h ago)    5d18h
orderfulfillment  postgres-...     4 (5d4h ago)    5d18h
orderfulfillment  order-service-...        1 (5d4h ago)   5d4h
orderfulfillment  inventory-service-...    1 (5d4h ago)   5d4h
orderfulfillment  payment-service-...      1 (5d4h ago)   5d4h
orderfulfillment  fulfillment-service-...  1 (5d4h ago)   5d4h
orderfulfillment  scenario-service-...     1 (5d4h ago)   5d4h

$ kubectl describe pod -n orderfulfillment <each pod> | grep -A8 "Last State"
# order/inventory/payment/scenario/kafka: Last State: Terminated, Reason: Error, Exit Code: 143
# fulfillment: Last State: Terminated, Reason: Unknown, Exit Code: 255
# postgres/frontend: Last State: Terminated, Reason: Completed, Exit Code: 0
# All "Finished" timestamps cluster in a single ~50-minute window on Fri 21 Aug 2026, 11:45-12:30,
# and all "Started" (current) timestamps land at 12:30:03-12:30:09 the same day.
# No pod anywhere shows Reason: OOMKilled.

$ kubectl get events -A --sort-by='.lastTimestamp' | tail -40
# Only one live event: a coredns DNSConfigForming warning (unrelated, nameserver list truncated).
# No memory-pressure or eviction events retained.

$ kubectl get events -A -o json | grep -i oom
# (no output)
```

Every pod's last restart timestamp falls inside the exact window ADR-011 documents as the
2026-08-21 incident and its reboot-based recovery — not something ongoing. Since then (5+ days),
zero restarts across the entire cluster, and no `OOMKilled` anywhere in pod history.

## Judgment calls

- **Attributed the ~790-810Mi gap (pod-sum vs. node-reported total) to k3s's own control-plane
  processes (embedded API server, scheduler, controller-manager, datastore), containerd, and
  kubelet — none of which are pods and so never show up in `kubectl top pods`, but which do count
  in `kubectl top nodes`'s node-level cAdvisor read.** I could not confirm this directly: the task
  authorized kubectl access only, not raw SSH to the box to run `free -h`/`ps aux` (the box is the
  production demo box, and the task's Access section was explicit about not improvising a different
  access path). This is the single largest unresolved number in this investigation — see
  Deliberately not covered.
- **Treated ADR-010's "memory is not the risk" conclusion as based on measurements that likely
  didn't include this overhead.** ADR-010 cites Phase 10 numbers gathered by standing the 8-pod
  stack up "inside" Docker Desktop's VM limit — i.e., against plain Docker/Compose or `kind`,
  neither of which runs a full k3s control plane (embedded API server + scheduler +
  controller-manager + datastore) as a separate long-lived process sharing the box's RAM the way a
  real k3s node does. If that inference is right, the original capacity argument in ADR-010 never
  priced in the ~800Mi the control plane itself appears to cost on the actual box, which would
  explain why 89% idle looks tighter than "memory is not the risk" would suggest. I'm flagging this
  as a likely explanation, not a confirmed one, for the same access-scope reason above.
- **Used 3 `kubectl top nodes` samples 20 seconds apart as "short-term stable," not as a trend
  check.** That's a real limit — it rules out second-to-second flapping, nothing more. I did not
  extrapolate it into "no leak exists"; the multi-day stability argument leans on the restart/OOM
  history instead (see below), which is a stronger signal than three samples a minute apart.
- **Leaned on "zero restarts and no OOMKilled for 5+ days" as the primary evidence for "not an
  active leak,"** on the reasoning that a JVM heap genuinely leaking toward its container limit
  would eventually get OOMKilled and restarted, and none of the five backend services have been
  restarted since the Aug 21 incident despite running continuously since. This is a real check
  against the two literal failure modes ADR-011 was written for, but it does not rule out a slow
  leak that hasn't yet crossed a limit — I could not get historical memory-over-time data to check
  that directly, since this cluster has no Prometheus/Grafana or other metrics retention beyond
  `metrics-server`'s live snapshot.

## Deliberately not covered

- **Could not directly measure or attribute the ~790-810Mi node-vs-pod-sum gap.** This is inferred
  (k3s control plane + containerd + kubelet + OS), not measured, because the task's Access section
  authorized `kubectl` only, not SSH to the box for `free -h`/`ps aux`/`journalctl`. If this gap
  needs to be pinned down precisely (e.g., to decide whether it is itself growing over time,
  independent of the pods), that requires either host-level access explicitly authorized as its own
  decision, or standing up a lightweight metrics/history solution (Prometheus, or even a cron'd
  `kubectl top nodes >> logfile` on the box) — not something to improvise mid-investigation.
- **No multi-day memory trend data exists to check against.** `metrics-server` keeps no history;
  there's no Prometheus/Grafana in this cluster. I cannot state whether 89% today is higher, lower,
  or the same as 89% a week ago, only that it has been flat across the ~1-minute window I sampled
  and that no pod has needed a restart in 5+ days at whatever level it's been at. If leak-hunting
  becomes the goal, this is the concrete gap to close first — some form of retained history, even a
  crude polling script logging `kubectl top nodes`/`kubectl top pods` to a file on the box.
- **Did not run or rehearse an actual `/deploy` beyond what the user already ran (the Stage 0
  `--dry-run` that surfaced this 89% figure).** No rolling restart, no `redeploy.sh`, nothing that
  touches a running pod. This report only reasons through the restart mechanics against the
  evidence gathered; it does not verify them by executing a real restart.
- **Did not check GC logs, JFR, or any in-JVM diagnostic for the five backend services** (e.g., via
  `kubectl exec` + `jcmd`) to see actual heap composition or GC pause behavior. Out of scope for a
  read-only top-level investigation, and `kubectl exec` into a production JVM to run diagnostics is
  arguably its own small risk-of-disruption decision better made explicitly if this becomes
  necessary.

## Bottom line

**Per-pod memory is healthy and matches the tuning's design.** All five backend JVMs sit at
52-57% of their 640Mi limit, Kafka at 62% of 1Gi, Postgres at 22% of 512Mi — none show heap
pressure, none have ever been `OOMKilled`, and none have restarted in the 5+ days since the
Aug 21 incident ADR-011 documents (every pod's last-restart timestamp falls inside that incident's
window, not since). The 89% figure is a **node-level** number, not a per-service one: pods across
the whole box (both namespaces) sum to only ~2.6GiB of the ~3.4-3.42GiB `kubectl top nodes` reports,
and the ~800Mi difference is almost certainly k3s's own control-plane/containerd/kubelet overhead —
a cost ADR-010's original sizing argument likely didn't price in, since it was based on Docker
Compose/`kind` measurements, not a running k3s node.

**Mechanically, a `redeploy.sh`-driven sequential restart should not make this worse.**
`maxSurge: 0, maxUnavailable: 1` on all five backend Deployments tears the old pod down (freeing its
~330-360Mi) *before* the new one starts and begins climbing back up — a swap, not a sum — and
`redeploy.sh` only ever has one service in that transient state at a time, gated on
`kubectl rollout status` succeeding before moving to the next. This is exactly the mechanism
ADR-011 put in place and it still applies at today's baseline the same way it did when verified.

**The margin is real but thinner than the ADRs assumed, and that's worth naming rather than
proceeding past silently.** ~400Mi of slack over an 800Mi-ish chunk that's structural overhead,
not app pods, means there's less cushion than "memory is not the risk" (ADR-010) implied for
absorbing a startup transient — JVM cold starts routinely spike above their eventual steady state
before GC settles, and that's the one thing this investigation did not get to measure directly
(would require watching `kubectl top pods` live through an actual restart). Recommend: it is
reasonable to proceed with a real `/deploy`, but watch `kubectl top nodes`/`kubectl top pods -n
orderfulfillment` live during the run rather than relying only on `rollout status` succeeding —
a pod can flip Ready while its memory is still climbing toward steady state. Separately, this
~800Mi control-plane overhead is a genuine gap in ADR-010/ADR-011's capacity model and is worth its
own follow-up: either a short addendum documenting the real idle baseline (so the next person
doesn't have to re-derive it under time pressure before a deploy), or revisiting the
already-flagged second-CX23-agent-node option now that idle sits at 89% rather than whatever it was
assumed to be when ADR-011 called sequential rollouts sufficient on their own.
