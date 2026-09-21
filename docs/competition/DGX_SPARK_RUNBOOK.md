# DGX Spark Competition Runbook

## Configuration

Gateway requires:

```text
MODEL_BASE_URL       OpenAI-compatible vLLM `/v1` endpoint
MODEL_NAME           exact remote model name; no repository default
MODEL_API_KEY        optional for local vLLM
AGENT_GATEWAY_TOKEN  bearer token shared with the Desktop demo environment
AGENT_GATEWAY_BIND_HOST=0.0.0.0
AGENT_GATEWAY_PORT=8091
```

Desktop requires:

```text
AGENT_GATEWAY_URL    e.g. https://<remote-gateway>
AGENT_GATEWAY_TOKEN  same demo token; inject at process launch only
```

Never commit real values or place model credentials in the Desktop bundle.

## Operator flow

```text
1. Start/verify the remote vLLM endpoint.
2. Start :server:agent-gateway with the required environment.
3. Check GET /health and GET /v1/model/health.
4. Start Desktop with AGENT_GATEWAY_URL and AGENT_GATEWAY_TOKEN.
5. Run read → proposed write → confirmation → Planner preview/apply demo.
6. Capture the response, Tool call, MutationId, and History evidence.
```

## Failure checks

```text
missing token       → 401
model unavailable   → structured gateway/model failure
malformed tool call → rejected before local execution
rejected confirmation → no MutationId and no state change
stale PlanBranch    → stale result; preview again
```

## Resume ledger

Record the last completed DGX milestone, exact model/runtime, remote endpoint
constraints, commands run, and unresolved failures in
`docs/tasks/DGX_SPARK_AGENT_SKILL_2026.md`.
