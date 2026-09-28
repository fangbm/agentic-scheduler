# Agentic Scheduler · DGX Spark Web Demo

Responsive, dependency-free Web UI based on the recovered original visual concept boards from earlier project conversations: **Calm & Modern — AI First**, **Dark & Focus**, and **Native & Integrated**. The source images are in the user's ChatGPT files, not this repository. This repo includes their visual decisions in `docs/competition/WEB_DEMO_SPRINT_20260928.md`, not counterfeit image assets.

## Start

Requires Node.js 20+; there is no `npm install` step and no frontend model key.

```bash
cd apps/web-demo
npm start
# Open http://127.0.0.1:4173
```

Default is **deterministic sandbox** mode with illustrative schedule and memory-only tasks, not a real model. Try `列出我的任务`, then `创建一个准备演示的任务`; review, deny, and confirm proposals. Demo writes never claim real `MutationId` or product `ChangeLog`.

## Optional remote DGX inference

Start the existing Kotlin `:server:agent-gateway` from the original `hackathon/dgx-spark-agent-skill` branch; configure that Gateway's `MODEL_BASE_URL`, `MODEL_NAME`, and `AGENT_GATEWAY_TOKEN` according to `docs/competition/DGX_SPARK_RUNBOOK.md`. Then run the separate Web demo bridge with:

```bash
AGENT_GATEWAY_URL=https://your-private-gateway \
AGENT_GATEWAY_TOKEN='your-demo-token' npm start
```

The Node bridge sends prompts and illustrative sandbox facts to the remote Gateway, and receives a structured `AgentTurnResponseV1` from `POST /v1/agent/turn`. It does **not** expose its bearer token to the browser. Remote HTTP is refused except on loopback. The Web demo bridge binds to loopback only and has **no public-user authentication**; do not publish it directly. Put a separately secured reverse proxy and proper authentication in front for access from another machine.

**Important actual limit:** Remote model Tool proposals are displayed, and `task.list`/`task.get` use sample Web-process data. Confirmed `task.create` writes only to this demo process memory. There is no Kotlin/Room trusted local write bridge or real Planner apply connected to the Web UI yet. The remote Gateway's old V1 protocol is not used here for a second model round-trip after ToolResults; don't claim a full multi-round real Agent execution until the underlying wire flow is upgraded and tested.

If Gateway errors, the UI shows an error and **does not fake a fallback model success**. Turn off remote mode by restarting without the Gateway environment variables.

## Test

```bash
npm test
```

The tests verify static delivery, sandbox mode, task reads, mandatory confirmation, denial no-write, approval sandbox-only, reset, and malformed request handling. After code or configuration changes, independently run the **real DGX remote health/model tests** and capture evidence before adding production claims to competition materials.

## Submission handoff

See `docs/competition/WEB_DEMO_SPRINT_20260928.md`, `docs/competition/DGX_SPARK_RUNBOOK.md`, and `docs/competition/DGX_SPARK_SUBMISSION_ZH.md`. To record a credible demo, capture both a browser screen recording and a separate real DGX health/model test; only combine them in the description if the real connection was verified.
