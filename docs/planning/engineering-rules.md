# AI agent implementation guidance

Engineering constraints for building this system. They apply to anyone working on it, agent or not,
and the frozen contracts and ADRs cite them by rule number.

How work is planned, delegated, and verified is a separate concern — see `docs/workflow/user-guide.md`
and `docs/workflow/agent-workflow.md`.

## Core directive

Build the smallest coherent system that truthfully demonstrates the architecture described here.

Do not optimize for line count, service count, or technology count.

---

## Agent rules

1. Do not invent product requirements beyond this document unless required for implementation.
2. Prefer boring, conventional code over clever abstractions.
3. Keep service boundaries explicit.
4. Keep DTOs separate from persistence entities.
5. Do not expose JPA entities directly from controllers.
6. Use database migrations.
7. Provide meaningful automated tests.
8. Preserve idempotency and event metadata.
9. Keep demo APIs isolated under `/demo`.
10. Scenario behavior must be real, not frontend simulation.
11. Do not introduce extra infrastructure without documenting the reason.
12. Keep the project runnable at every major phase.
13. Favor incremental commits/milestones.
14. Add README instructions whenever startup requirements change.
15. Add an ADR for major architectural changes.
16. Avoid hidden magic in shared libraries.
17. Use consistent logging with correlation IDs.
18. Do not claim stronger delivery/consistency guarantees than are implemented.
19. Do not make Kubernetes a prerequisite for early local development.
20. Keep frontend styling polished but secondary to system visibility.

---

# Agent Coordination Rules

Use shared contracts as the integration boundary.

Before parallel work:

- freeze initial endpoint names,
- freeze event envelope,
- freeze initial event names,
- freeze order status enum,
- document database ownership.

When an agent needs a contract change:

1. modify contract documentation first,
2. state why,
3. update affected implementations,
4. add/adjust tests.

Avoid silent divergence.

---
