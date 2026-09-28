import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = dirname(fileURLToPath(import.meta.url));
const HOST = process.env.WEB_DEMO_HOST ?? '127.0.0.1';
const PORT = Number(process.env.WEB_DEMO_PORT ?? 4173);
const GATEWAY_URL = (process.env.AGENT_GATEWAY_URL ?? '').replace(/\/+$/, '');
const GATEWAY_TOKEN = process.env.AGENT_GATEWAY_TOKEN ?? '';
const REMOTE = Boolean(GATEWAY_URL && GATEWAY_TOKEN);

if (HOST !== '127.0.0.1' && HOST !== 'localhost' && HOST !== '::1') {
  throw new Error('Web demo may bind to loopback only. Use a separate authenticated reverse proxy for remote access.');
}
if (!Number.isInteger(PORT) || PORT < 1 || PORT > 65535) throw new Error('Invalid WEB_DEMO_PORT.');
if (REMOTE) {
  const endpoint = new URL(GATEWAY_URL);
  if (endpoint.protocol !== 'https:' && !(endpoint.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname))) {
    throw new Error('Remote Gateway requires HTTPS. HTTP is allowed only for loopback development.');
  }
}

const seedTasks = () => [
  { id: 'task-1001', title: '完成项目开发', priority: 'HIGH', completed: false, duration: 90 },
  { id: 'task-1002', title: '准备黑客松演示', priority: 'HIGH', completed: false, duration: 60 },
  { id: 'task-1003', title: '整理参赛材料', priority: 'NORMAL', completed: false, duration: 45 },
];
const seedEvents = () => [
  { id: 'ev-1', title: 'Morning Focus', subtitle: 'Deep work', start: '09:00', end: '10:30', kind: 'focus' },
  { id: 'ev-2', title: 'Algorithms', subtitle: 'Study session', start: '11:00', end: '12:00', kind: 'course' },
  { id: 'ev-3', title: 'Lunch', subtitle: 'Break', start: '12:30', end: '13:15', kind: 'break' },
  { id: 'ev-4', title: 'Group Project Meeting', subtitle: 'Room 3', start: '14:00', end: '15:30', kind: 'meeting' },
  { id: 'ev-5', title: 'Demo preparation', subtitle: 'Practice presentation', start: '17:00', end: '18:30', kind: 'health' },
  { id: 'ev-6', title: 'Read & Review', subtitle: 'Deep focus', start: '19:00', end: '20:00', kind: 'focus' },
];
const data = { tasks: seedTasks(), events: seedEvents(), changes: [], pending: new Map() };
const MAX_INPUT = 16_384;
const LIMIT_PENDING = 20;

function send(res, code, value) {
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
    'content-security-policy': "default-src 'none'; frame-ancestors 'none'",
  });
  res.end(JSON.stringify(value));
}
function sampleState() {
  return { mode: REMOTE ? 'gateway' : 'sandbox', today: new Date().toISOString().slice(0, 10),
    tasks: data.tasks, events: data.events, changes: data.changes, pendingCount: data.pending.size };
}
async function bodyJson(req) {
  if (req.headers['content-type']?.split(';')[0]?.trim().toLowerCase() !== 'application/json') throw Object.assign(new Error('JSON_REQUIRED'), { status: 415 });
  let text = '';
  for await (const chunk of req) {
    text += chunk.toString('utf8');
    if (Buffer.byteLength(text, 'utf8') > MAX_INPUT) throw Object.assign(new Error('REQUEST_TOO_LARGE'), { status: 413 });
  }
  try { return JSON.parse(text); } catch { throw Object.assign(new Error('INVALID_JSON'), { status: 400 }); }
}
function strictCreateArgs(args) {
  if (!args || typeof args !== 'object' || Array.isArray(args)) return null;
  const keys = Object.keys(args);
  if (keys.some(k => !['title', 'priority', 'estimatedMinutes', 'remainingMinutes'].includes(k))) return null;
  const title = args.title;
  const priority = args.priority;
  const estimatedMinutes = args.estimatedMinutes;
  const remainingMinutes = args.remainingMinutes;
  if (typeof title !== 'string' || !title.trim() || title.length > 100 || !['LOW', 'NORMAL', 'HIGH'].includes(priority)) return null;
  if (!Number.isInteger(estimatedMinutes) || estimatedMinutes < 0 || estimatedMinutes > 10_080 ||
      !Number.isInteger(remainingMinutes) || remainingMinutes < 0 || remainingMinutes > 10_080) return null;
  return { title: title.trim(), priority, estimatedMinutes, remainingMinutes };
}
function parseArgs(raw) {
  try { return JSON.parse(raw); } catch { return null; }
}
function proposeCreate(args) {
  const validated = strictCreateArgs(args);
  if (!validated) return { error: 'INVALID_TOOL_ARGUMENTS' };
  if (data.pending.size >= LIMIT_PENDING) return { error: 'PENDING_LIMIT' };
  const id = randomUUID();
  data.pending.set(id, validated);
  return { pending: { id, name: 'task.create', preview: validated, mode: REMOTE ? 'gateway+sandbox-executor' : 'sandbox' } };
}
function readTool(tool) {
  if (tool.name === 'task.list') return { tool: tool.name, status: 'SUCCESS', data: data.tasks.map(({ id, title, priority, completed }) => ({ id, title, priority, completed })) };
  if (tool.name === 'task.get') {
    const args = parseArgs(tool.argumentsJson);
    const task = typeof args?.taskId === 'string' ? data.tasks.find(t => t.id === args.taskId) : null;
    return { tool: tool.name, status: task ? 'SUCCESS' : 'NOT_FOUND', data: task ?? null };
  }
  return { tool: tool.name, status: 'UNSUPPORTED_TOOL', data: null };
}
function guessTitle(text) {
  const matched = text.match(/(?:创建|添加|新增)(?:一个|一项|一条)?(?:名为|叫作|叫|任务[:：]?)?\s*[“"']?([^。，！？!?.\n]{2,45})/);
  return matched?.[1]?.replace(/[”"']$/, '').trim() || '准备黑客松演示';
}
function sandboxTurn(prompt) {
  const trace = [];
  if (/(创建|添加|新增|create|add)/i.test(prompt) && /(任务|task|准备|演示)/i.test(prompt)) {
    const title = guessTitle(prompt);
    const proposal = proposeCreate({ title, priority: 'HIGH', estimatedMinutes: 45, remainingMinutes: 45 });
    trace.push({ tool: 'task.create', status: proposal.error ? 'INVALID_INPUT' : 'AWAITING_CONFIRMATION' });
    return { message: proposal.error ? `沙盒无法创建提案：${proposal.error}` : '已生成一份沙盒任务创建提案。请先检查内容，再选择确认或拒绝。',
      trace, pending: proposal.pending ?? null, origin: 'deterministic-sandbox' };
  }
  if (/(任务|task|有哪些|计划)/i.test(prompt)) {
    const result = readTool({ name: 'task.list' });
    trace.push(result);
    return { message: `演示沙盒目前有 ${data.tasks.length} 项任务。这份列表来自本地示例数据，并非模型推理或真实 Room 数据库。`, trace, pending: null, origin: 'deterministic-sandbox' };
  }
  if (/(调整|优化|planner|重排|replan|时间)/i.test(prompt)) {
    return { message: '目前可以展示日程界面；真实 Planner 预览和应用必须等可信 Kotlin Bridge 接通后才能操作。本沙盒不会伪造 Planner 结果。', trace, pending: null, origin: 'deterministic-sandbox' };
  }
  return { message: '这里是可交互的演示沙盒。试试「列出我的任务」或「创建一个准备演示的任务」。未配置 DGX Gateway 时不会假装调用真实模型。', trace, pending: null, origin: 'deterministic-sandbox' };
}
async function gatewayTurn(prompt) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20_000);
  const runId = randomUUID();
  try {
    const resp = await fetch(`${GATEWAY_URL}/v1/agent/turn`, {
      method: 'POST', signal: controller.signal,
      headers: { authorization: `Bearer ${GATEWAY_TOKEN}`, 'content-type': 'application/json', 'accept': 'application/json' },
      body: JSON.stringify({ runId, messages: [{ role: 'user', content: prompt }], contextJson: JSON.stringify({ tasks: data.tasks, events: data.events }), toolResults: [] }),
    });
    if (!resp.ok) return { error: `GATEWAY_HTTP_${resp.status}` };
    const parsed = await resp.json();
    if (parsed?.runId !== runId || !Array.isArray(parsed.toolCalls) || (parsed.assistantText !== null && parsed.assistantText !== undefined && typeof parsed.assistantText !== 'string')) {
      return { error: 'MALFORMED_GATEWAY_RESPONSE' };
    }
    const trace = [];
    let pending = null;
    for (const call of parsed.toolCalls.slice(0, 8)) {
      if (typeof call?.id !== 'string' || typeof call?.name !== 'string' || typeof call.argumentsJson !== 'string') {
        trace.push({ tool: 'unknown', status: 'INVALID_TOOL_CALL' }); continue;
      }
      if (call.name === 'task.create') {
        if (pending) { trace.push({ tool: 'task.create', status: 'ONE_WRITE_AT_A_TIME' }); continue; }
        const proposal = proposeCreate(parseArgs(call.argumentsJson));
        trace.push({ tool: call.name, status: proposal.error ?? 'AWAITING_CONFIRMATION' });
        pending = proposal.pending ?? null;
      } else {
        trace.push(readTool(call));
      }
    }
    const suffix = trace.length ? ' 当前工具结果由网页沙盒执行，尚未回传给模型；不代表产品真实业务数据。' : '';
    return { message: (parsed.assistantText || (trace.length ? '远程 Gateway 返回了结构化 Tool 提案。' : '远程 Gateway 返回了空文本。')) + suffix,
      trace, pending, origin: 'remote-gateway+sandbox-executor' };
  } catch (error) {
    return { error: error?.name === 'AbortError' ? 'GATEWAY_TIMEOUT' : 'GATEWAY_UNAVAILABLE' };
  } finally { clearTimeout(timeout); }
}
function confirm(id, approved) {
  if (typeof id !== 'string' || typeof approved !== 'boolean') return { error: 'INVALID_CONFIRMATION' };
  const preview = data.pending.get(id);
  if (!preview) return { error: 'CONFIRMATION_NOT_FOUND' };
  data.pending.delete(id);
  if (!approved) return { status: 'DENIED', message: '已拒绝；沙盒任务和业务操作记录没有变化。' };
  const task = { id: `sandbox-${randomUUID().slice(0, 8)}`, title: preview.title, priority: preview.priority, completed: false, duration: preview.estimatedMinutes };
  data.tasks.push(task);
  const record = { id: `demo-${randomUUID().slice(0, 8)}`, kind: 'TASK_CREATED_IN_SANDBOX', target: task.title, time: new Date().toISOString() };
  data.changes.unshift(record);
  return { status: 'SANDBOX_COMMITTED', message: '已在内存沙盒中添加任务；非真实 MutationId / ChangeLog。', task, record };
}
const staticFiles = new Map([
  ['/', ['public/index.html', 'text/html; charset=utf-8']],
  ['/styles.css', ['public/styles.css', 'text/css; charset=utf-8']],
  ['/app.js', ['public/app.js', 'text/javascript; charset=utf-8']],
]);
export const app = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://127.0.0.1');
  try {
    if (req.method === 'GET' && url.pathname === '/api/state') return send(res, 200, sampleState());
    if (req.method === 'GET' && url.pathname === '/api/status') return send(res, 200, { mode: REMOTE ? 'gateway' : 'sandbox', gatewayConfigured: REMOTE,
      note: REMOTE ? 'Remote inference configured; write tools still use memory-only sandbox execution.' : 'Deterministic sandbox: no remote model call.' });
    if (req.method === 'POST' && url.pathname === '/api/agent/turn') {
      const value = await bodyJson(req);
      if (typeof value?.prompt !== 'string' || !value.prompt.trim() || value.prompt.length > 1_000) return send(res, 422, { error: 'INVALID_PROMPT' });
      const result = REMOTE ? await gatewayTurn(value.prompt.trim()) : sandboxTurn(value.prompt.trim());
      return send(res, result.error ? 502 : 200, result);
    }
    if (req.method === 'POST' && url.pathname === '/api/confirm') {
      const value = await bodyJson(req);
      const result = confirm(value?.id, value?.approved);
      return send(res, result.error ? 422 : 200, result);
    }
    if (req.method === 'POST' && url.pathname === '/api/reset') {
      // Reset is explicitly sandbox-only. The real Gateway is never reset by this endpoint.
      data.tasks = seedTasks(); data.events = seedEvents(); data.changes = []; data.pending.clear();
      return send(res, 200, sampleState());
    }
    const file = staticFiles.get(url.pathname);
    if (req.method === 'GET' && file) {
      const bytes = await readFile(join(ROOT, file[0]));
      res.writeHead(200, { 'content-type': file[1], 'cache-control': 'no-store', 'x-content-type-options': 'nosniff',
        'content-security-policy': "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'" });
      return res.end(bytes);
    }
    return send(res, 404, { error: 'NOT_FOUND' });
  } catch (error) {
    return send(res, error?.status ?? 500, { error: error?.status ? error.message : 'INTERNAL_ERROR' });
  }
});
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  app.listen(PORT, HOST, () => console.log(`Web demo listening on http://${HOST}:${PORT} (${REMOTE ? 'gateway+sandbox-executor' : 'sandbox'} mode)`));
}
