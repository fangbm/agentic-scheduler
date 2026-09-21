# DGX Spark Agent Skills Competition — Preliminary Demo

> Status: **IN PROGRESS — competition branch only**
> Branch: `hackathon/dgx-spark-agent-skill`
> Baseline: merged `main` commit `3b74a84`
> Last verified: 2026-09-21

Current implementation status: DGX-00 docs/branch baseline, DGX-01 shared
wire/run-loop scaffolding, DGX-02 authenticated gateway scaffolding, and the
Desktop connection panel are present. Local targeted Gradle verification is
blocked until this host uses JDK 17; the repository currently resolves Java 8.
The first local read Tools (`task.list` and `task.get`) are wired. The Desktop
now holds write proposals in a visible confirmation dialog; confirmed
`task.create` is the first local mutation path. Other writes return
`CONFIRMATION_REQUIRED` without mutating state. The next slice is the remote
DGX smoke run and evidence capture.

## Competition record

This task targets the registered **3rd NVIDIA DGX Spark Hackathon — Agent
Skills Development Challenge**.

References:

- [NVIDIA registration page](https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920?pushId=pYNqcHXQcZKzKiQmpfsb5G1)
- [DGX Spark developer portal](https://build.nvidia.com/spark)
- [Public event schedule mirror](https://awnchina.cn/3rd-nvidia-dgx-spark-hackathon-%C2%B7-agent-skills-development-challenge/)

```text
registration deadline       2026-09-19
training / rules briefing  2026-09-20
preliminary submission      2026-09-20 .. 2026-09-29
preliminary evaluation      2026-09-30 .. 2026-10-08
final project demo          2026-10-15
```

The public announcement describes Agent Skills as domain expertise plus tool
calling and task execution. Record private submission requirements here when
the team supplies them; do not invent missing contest rules.

## Scope and trust model

The preliminary deliverable is a **Desktop-to-remote-DGX general scheduler
Agent demo**. It is not a rewrite of the main product.

```text
Desktop Agent UI
    → authenticated HTTPS Agent Gateway on DGX
    → configured OpenAI-compatible vLLM endpoint
    → typed Tool proposal
    → Desktop validation and confirmation
    → existing application service / Planner / Mutation
    → local authoritative Room state and ChangeLog
```

The gateway is stateless and does not persist schedule data. During inference,
the gateway/model can see selected prompts and structured schedule facts. This
is a competition-branch trust-model disclosure; `main` keeps Local-first/E2EE.

Required security rules: HTTPS outside local development, bearer gateway auth,
DGX-only model credentials, no prompt/schedule/secret bodies in ordinary logs,
no direct model-to-database writes, and mandatory typed Tool validation plus
local permission/confirmation.

## Preliminary exclusions

Do not add these before the preliminary submission:

```text
server-authoritative schedule persistence
PostgreSQL schedule tables
WebUI
Android remote-server mode
Wear support
offline writes or multi-writer merge
full D9 conversation persistence/compaction
generic SQL, shell, browser, or arbitrary JSON Tools
```

## Implementation contract

Authorize these competition-only modules:

```text
:shared:agent
:server:agent-gateway
```

`:shared:agent` owns the minimal run loop, skill definition, Tool registry,
permission checks, typed request/response models, and current-session trace.
It may depend on Domain, Application, Planner, and Sync typed IDs; it must not
depend on Room records or server implementation classes.

`:server:agent-gateway` owns HTTP auth, model configuration, vLLM requests,
response validation, and redacted infrastructure errors. It owns no schedule
state and no business mutation service.

Gateway configuration is explicit and has no production defaults:

```text
MODEL_BASE_URL       required
MODEL_NAME           required
MODEL_API_KEY        optional for local vLLM
AGENT_GATEWAY_TOKEN  required
```

The Desktop app configures `serverBaseUrl`; the preliminary token is injected
through `AGENT_GATEWAY_TOKEN` and is never written to Room or source control.

## Typed Tool surface

Initial tools are exactly:

```text
calendar.list
task.get
task.list
event.create
event.update
task.create
task.update
planner.previewFullReplan
planner.applyBranch
history.timeline
```

Reads and previews are direct. Writes require confirmation and revalidate
current state immediately before execution. Planner application uses existing
stale/atomic PlanBranch behavior. Every committed write returns its MutationId;
a failed operation never becomes a successful Agent result.

The run loop caps one turn at eight Tool calls and rejects unknown tools,
malformed inputs, unsupported provider tool-call responses, and missing
mandatory context with structured failures.

## Milestones

```text
DGX-00  freeze this task spec and branch baseline
DGX-01  shared typed Agent loop + fake-provider tests
DGX-02  authenticated Ktor gateway + vLLM adapter + health checks
DGX-03  Desktop Agent panel + confirmation/PlanBranch result cards
DGX-04  remote DGX deployment and end-to-end smoke
DGX-05  Chinese submission pack, demo script, evidence, and handoff ledger
```

## Acceptance gate

- Desktop connects using `serverBaseUrl` and injected gateway auth.
- DGX model configuration is server-side and changeable without a client build.
- Reads return current structured facts.
- Creates/updates produce typed Tool proposals and require confirmation.
- Rejected proposals perform no write.
- Planner requests show a preview and apply through the existing atomic path.
- MutationId/history evidence is visible after a committed write.
- Provider failure, malformed output, stale preview, and unsupported Tool calling are explicit failures.
- Gateway authentication and redacted logging tests pass.
- Remote DGX smoke passes for health, read, confirmed write, preview/apply, and history.
- Chinese submission document, setup runbook, screenshots/video script, and known-limitations list are complete.

## Resume checklist

Read this file first when continuing. Inspect branch status and the last
completed DGX milestone. Record actual commands, model name, endpoint limits,
test results, and remaining work here before changing scope.

Submission draft: [`docs/competition/DGX_SPARK_SUBMISSION_ZH.md`](../competition/DGX_SPARK_SUBMISSION_ZH.md)

Operator runbook: [`docs/competition/DGX_SPARK_RUNBOOK.md`](../competition/DGX_SPARK_RUNBOOK.md)
