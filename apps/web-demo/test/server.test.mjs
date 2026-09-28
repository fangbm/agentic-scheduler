import assert from 'node:assert/strict';
import { test, before, after } from 'node:test';
import { once } from 'node:events';
import { app } from '../server.mjs';

let base;
before(async () => { app.listen(0, '127.0.0.1'); await once(app, 'listening'); base = `http://127.0.0.1:${app.address().port}`; });
after(async () => { app.close(); await once(app, 'close'); });
const json = async (path, method = 'GET', body) => {
  const response = await fetch(base + path, { method, headers: { 'content-type': 'application/json' }, body: method === 'GET' ? undefined : JSON.stringify(body) });
  return { status: response.status, body: await response.json() };
};

test('static page loads and never embeds gateway credentials', async () => {
  const response = await fetch(base);
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /Agentic Scheduler/);
  assert.match(html, /Agent/);
  assert.doesNotMatch(html, /AGENT_GATEWAY_TOKEN|MODEL_API_KEY/);
});
test('reports deterministic sandbox mode without gateway configuration', async () => {
  const r = await json('/api/status');
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.gatewayConfigured, false);
  assert.equal(r.body.mode, 'sandbox');
});
test('reads sample tasks without mutating demo state', async () => {
  const beforeState = (await json('/api/state')).body;
  const turn = await json('/api/agent/turn', 'POST', { prompt: '列出我的任务' });
  assert.equal(turn.status, 200);
  assert.equal(turn.body.origin, 'deterministic-sandbox');
  assert.equal(turn.body.trace[0].tool, 'task.list');
  const afterState = (await json('/api/state')).body;
  assert.deepEqual(afterState.tasks, beforeState.tasks);
  assert.deepEqual(afterState.changes, beforeState.changes);
});
test('task create requires explicit approval and denial changes no task', async () => {
  await json('/api/reset', 'POST', {});
  const beforeState = (await json('/api/state')).body;
  const proposal = await json('/api/agent/turn', 'POST', { prompt: '创建一个准备黑客松演示的任务' });
  assert.equal(proposal.status, 200);
  assert.equal(proposal.body.trace[0].status, 'AWAITING_CONFIRMATION');
  assert.ok(proposal.body.pending?.id);
  assert.equal((await json('/api/state')).body.tasks.length, beforeState.tasks.length);
  const denied = await json('/api/confirm', 'POST', { id: proposal.body.pending.id, approved: false });
  assert.equal(denied.body.status, 'DENIED');
  const after = (await json('/api/state')).body;
  assert.deepEqual(after.tasks, beforeState.tasks);
  assert.deepEqual(after.changes, beforeState.changes);
  const secondAttempt = await json('/api/confirm', 'POST', { id: proposal.body.pending.id, approved: true });
  assert.equal(secondAttempt.status, 422);
  assert.equal(secondAttempt.body.error, 'CONFIRMATION_NOT_FOUND');
});
test('approved task is visibly sandbox-only; reset clears tasks and pending approvals', async () => {
  const beforeCount = (await json('/api/state')).body.tasks.length;
  const turn = await json('/api/agent/turn', 'POST', { prompt: '创建一个演示用任务' });
  assert.ok(turn.body.pending);
  const result = await json('/api/confirm', 'POST', { id: turn.body.pending.id, approved: true });
  assert.equal(result.body.status, 'SANDBOX_COMMITTED');
  assert.match(result.body.task.id, /^sandbox-/);
  assert.match(result.body.record.id, /^demo-/);
  const after = (await json('/api/state')).body;
  assert.equal(after.tasks.length, beforeCount + 1);
  assert.equal(after.changes.length, 1);
  await json('/api/reset', 'POST', {});
  const reset = (await json('/api/state')).body;
  assert.equal(reset.tasks.length, 3);
  assert.equal(reset.changes.length, 0);
  assert.equal(reset.pendingCount, 0);
});
test('malformed and unsupported requests fail closed', async () => {
  assert.equal((await json('/api/agent/turn', 'POST', { prompt: '   ' })).status, 422);
  assert.equal((await json('/api/confirm', 'POST', { id: 'not-pending', approved: true })).status, 422);
  assert.equal((await json('/../../server.mjs')).status, 404);
  assert.equal((await json('/api/unknown')).status, 404);
});
