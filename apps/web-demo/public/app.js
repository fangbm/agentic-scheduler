const $ = id => document.getElementById(id);
const app = {
  view: 'today',
  data: { tasks: [], events: [], changes: [], mode: 'sandbox' },
  pending: null,
  busy: false,
  messages: [],
};
function escapeHtml(raw) {
  return String(raw ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
}
function formatDate(now, opts) { return new Intl.DateTimeFormat('en-US', opts).format(now); }
function timeMinutes(value) { const [h, m] = String(value).split(':').map(Number); return h * 60 + m; }
function prettyClock(value) { return value; }
function todayText() {
  const now = new Date();
  const greeting = now.getHours() < 12 ? 'Good morning' : now.getHours() < 18 ? 'Good afternoon' : 'Good evening';
  $('main-title').innerHTML = `${greeting} <span class="wave">✳</span>`;
  $('today-overline').textContent = formatDate(now, { weekday: 'long', month: 'long', day: 'numeric' }).toUpperCase();
  $('date-tag').textContent = formatDate(now, { month: 'short', day: 'numeric', year: 'numeric' });
}
function renderMiniCalendar() {
  const now = new Date();
  const month = now.getMonth();
  const year = now.getFullYear();
  const firstWeekday = (new Date(year, month, 1).getDay() + 6) % 7;
  const days = new Date(year, month + 1, 0).getDate();
  let squares = ['M', 'T', 'W', 'T', 'F', 'S', 'S'].map(x => `<span style="font-weight:760">${x}</span>`).join('');
  for (let i = 0; i < firstWeekday; i++) squares += '<span></span>';
  for (let date = 1; date <= days; date++) squares += `<span class="${date === now.getDate() ? 'today' : ''}">${date}</span>`;
  return `<section class="mini-card"><div class="mini-head">${escapeHtml(formatDate(now, { month: 'long', year: 'numeric' }))}<span class="mini-sub">◂ &nbsp; ▸</span></div><div class="mini-calendar">${squares}</div></section>`;
}
function renderMiniTasks() {
  const count = app.data.tasks.length;
  const taskRows = app.data.tasks.slice(0, 4).map(t => `<div class="task-mini-row"><input class="mini-task-checkbox" aria-label="Task preview" type="checkbox" ${t.completed ? 'checked' : ''} tabindex="-1"><span>${escapeHtml(t.title)}</span><span class="priority ${t.priority === 'HIGH' ? 'high' : ''}">${escapeHtml(t.priority)}</span></div>`).join('');
  return `<section class="mini-card"><div class="mini-head">Tasks <span class="mini-sub">${count} tasks</span></div><div class="mini-stats"><div class="progress-ring" role="img" aria-label="Static illustrative progress ring"></div><div><strong>3.5 / 6 h</strong><small>Illustrative focus goal</small></div></div><div class="task-mini">${taskRows}</div></section>`;
}
function renderSchedule() {
  const base = 8 * 60;
  const heightPerMinute = 1.15;
  const end = 21 * 60;
  let grid = '';
  for (let hour = 8; hour <= 21; hour++) {
    const top = (hour * 60 - base) * heightPerMinute;
    grid += `<div class="timeline-hour" style="top:${top}px"><span class="hour-label">${String(hour).padStart(2, '0')}:00</span></div>`;
  }
  const cards = app.data.events.map(event => {
    const start = timeMinutes(event.start);
    const finish = timeMinutes(event.end);
    const top = Math.max(0, (start - base) * heightPerMinute);
    const height = Math.max(29, (finish - start) * heightPerMinute - 3);
    return `<div class="timeline-event ${escapeHtml(event.kind)}" style="top:${top}px;height:${height}px" title="${escapeHtml(event.title)} (${escapeHtml(event.start)}–${escapeHtml(event.end)})"><b>${escapeHtml(event.title)}</b><span>${escapeHtml(prettyClock(event.start))} – ${escapeHtml(prettyClock(event.end))} · ${escapeHtml(event.subtitle)}</span></div>`;
  }).join('');
  const now = new Date();
  const minutes = now.getHours() * 60 + now.getMinutes();
  const nowLine = minutes > base && minutes < end ? `<div class="time-now" title="当前时间" style="top:${(minutes - base) * heightPerMinute}px"></div>` : '';
  grid += cards + nowLine;
  return `<div class="schedule-grid"><section class="timeline-card"><div class="timeline-scroll"><div class="timeline" style="height:${(end - base) * heightPerMinute + 20}px">${grid}</div></div></section><div class="side-cards">${renderMiniCalendar()}${renderMiniTasks()}</div></div>`;
}
function renderTaskList() {
  if (!app.data.tasks.length) return '<p class="loading-card">尚无沙盒任务。试着让 Agent 创建一项任务。</p>';
  return `<section class="tasks-view">${app.data.tasks.map(t => `<div class="task-list-row"><span class="insight-icon ${t.priority === 'HIGH' ? 'warm' : 'mint'}"><svg><use href="#i-checks"/></svg></span><div><strong>${escapeHtml(t.title)}</strong><small>Estimated ${Number(t.duration) || 0} min · Demo sandbox</small></div><span class="task-priority">${escapeHtml(t.priority)}</span></div>`).join('')}</section>`;
}
function renderContent() {
  const titles = {
    today: ['Your schedule', 'Stay on top of everything that matters.'],
    calendar: ['Calendar', 'A focused view of today’s time blocks.'],
    tasks: ['Your tasks', 'The tasks below are demo sandbox examples.'],
    agent: ['Your intelligent workspace', 'Connect a DGX model, review proposals and retain control.'],
  };
  const [title, subtitle] = titles[app.view] ?? titles.today;
  $('breadcrumb-label').textContent = app.view[0].toUpperCase() + app.view.slice(1);
  $('section-title').textContent = title;
  $('section-sub').textContent = subtitle;
  $('content').innerHTML = app.view === 'tasks' ? renderTaskList() : app.view === 'agent'
    ? `<div class="focus-placeholder"><svg><use href="#i-spark"/></svg><h3>Your AI copilot is right here</h3><p>Chat with Agent in the right panel. Every write proposal needs your review.<br>Connection mode: <b>${app.data.mode === 'gateway' ? 'Remote Gateway + sandbox executor' : 'Deterministic sandbox'}</b>.</p><button type="button" data-focus-agent="yes">Open Agent →</button></div>` : renderSchedule();
  document.querySelectorAll('.nav-item').forEach(btn => {
    const selected = btn.dataset.nav === app.view;
    btn.classList.toggle('selected', selected);
    if (selected) btn.setAttribute('aria-current', 'page'); else btn.removeAttribute('aria-current');
  });
  if (app.view === 'tasks' || app.view === 'agent') document.querySelector('.segmented').hidden = true;
  else document.querySelector('.segmented').hidden = false;
}
function renderActivity() {
  $('activity-items').innerHTML = app.data.changes.length ? app.data.changes.map(change => `<div class="activity-entry"><span class="bullet"></span><div><b>${escapeHtml(change.target)}</b><small>Memory-only sandbox operation · ${escapeHtml(new Date(change.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))}</small></div></div>`).join('') : '<p class="empty-activity">Your approved demo changes appear here.</p>';
}
function renderChat() {
  $('chat').innerHTML = app.messages.map(entry => {
    const trace = (entry.trace ?? []).map(t => `<div class="trace-line"><code>${escapeHtml(t.tool)}</code><span>${escapeHtml(t.status)}</span></div>${t.data ? `<div class="trace-line" style="margin-top:4px;color:var(--muted);font-size:9px">${escapeHtml(JSON.stringify(t.data).slice(0,350))}</div>` : ''}`).join('');
    return `<article class="message ${entry.role === 'user' ? 'user' : 'assistant'}"><span class="speaker">${entry.role === 'user' ? 'YOU' : entry.origin === 'remote-gateway+sandbox-executor' ? 'REMOTE MODEL · SANDBOX TOOLS' : 'DETERMINISTIC SANDBOX'}</span>${escapeHtml(entry.text)}</article>${trace ? `<section class="trace" aria-label="结构化 Tool trace"><div class="trace-header">TOOL TRACE · ${entry.origin === 'remote-gateway+sandbox-executor' ? 'REMOTE PROPOSAL / LOCAL DEMO DATA' : 'SANDBOX'}</div>${trace}</section>` : ''}`;
  }).join('');
}
function renderProposal() {
  const holder = $('proposal');
  if (!app.pending) { holder.innerHTML = ''; return; }
  const p = app.pending.preview;
  holder.innerHTML = `<article class="proposal-card"><div class="proposal-tag">✦ &nbsp; ACTION REQUIRES APPROVAL</div><h3>Review proposed change</h3><div class="proposal-preview"><b>${escapeHtml(p.title)}</b><div class="proposal-meta"><span>${escapeHtml(p.priority)} priority</span><span>·</span><span>${Number(p.estimatedMinutes)} min</span><span>·</span><span>New task</span></div></div><p class="proposal-foot">This action changes demo memory only. It is not a real Planner/Room mutation. You decide whether to apply it.</p><div class="proposal-buttons"><button class="decline" data-decision="deny" ${app.busy ? 'disabled' : ''}>Deny</button><button class="approve" data-decision="approve" ${app.busy ? 'disabled' : ''}>Confirm task</button></div></article>`;
}
function renderMode() {
  const gateway = app.data.mode === 'gateway';
  $('live-mode').className = `mode-chip ${gateway ? 'gateway' : ''}`;
  $('live-mode').innerHTML = `<span class="status-dot"></span>${gateway ? 'Remote model + demo tools' : 'Demo sandbox'}`;
  $('compose-mode').textContent = gateway ? '● Remote model / demo executor' : '● Deterministic sandbox';
  $('privacy-copy').textContent = gateway ? 'Prompts and sandbox facts go to the configured remote Gateway.' : 'Sandbox data stays in this local demo process.';
  $('footer-note').textContent = gateway ? 'Gateway model · Sandbox tools · Not production' : 'No model call · Memory-only demo';
}
function renderAll() { todayText(); renderContent(); renderActivity(); renderChat(); renderProposal(); renderMode(); }
async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { 'content-type': 'application/json' } });
  let data;
  try { data = await response.json(); } catch { throw new Error('服务器没有返回可解析的 JSON'); }
  if (!response.ok) throw new Error(data.error || `HTTP_${response.status}`);
  return data;
}
async function syncState() { app.data = await api('/api/state'); renderAll(); }
function showError(message) { const node = $('agent-error'); node.hidden = !message; node.textContent = message || ''; }
async function sendPrompt(prompt) {
  if (!prompt.trim() || app.busy) return;
  if (app.pending) { showError('请先确认或拒绝当前操作，再发起新的请求。'); return; }
  app.busy = true; showError('');
  $('send').disabled = true;
  $('send').innerHTML = '<span style="font-size:11px">…</span>';
  app.messages.push({ role: 'user', text: prompt });
  renderChat();
  $('prompt').value = '';
  $('agent-scroll').scrollTop = $('agent-scroll').scrollHeight;
  try {
    const response = await api('/api/agent/turn', { method: 'POST', body: JSON.stringify({ prompt }) });
    app.messages.push({ role: 'assistant', text: response.message, trace: response.trace, origin: response.origin });
    if (response.pending) app.pending = response.pending;
    await syncState();
  } catch (err) {
    showError(`请求失败：${err.message}。没有自动转入模拟模式。`);
    app.messages.push({ role: 'assistant', text: `请求失败：${err.message}`, origin: app.data.mode === 'gateway' ? 'remote-gateway+sandbox-executor' : 'deterministic-sandbox' });
    renderChat();
  } finally {
    app.busy = false;
    $('send').disabled = false;
    $('send').innerHTML = '<svg><use href="#i-arrow"/></svg>';
    renderProposal();
    $('agent-scroll').scrollTop = $('agent-scroll').scrollHeight;
  }
}
async function confirmAction(approved) {
  if (!app.pending || app.busy) return;
  app.busy = true; showError(''); renderProposal();
  try {
    const result = await api('/api/confirm', { method: 'POST', body: JSON.stringify({ id: app.pending.id, approved }) });
    app.pending = null;
    app.messages.push({ role: 'assistant', text: result.message, origin: 'deterministic-sandbox' });
    await syncState();
  } catch (err) { showError(`确认处理失败：${err.message}`); }
  finally { app.busy = false; renderProposal(); $('agent-scroll').scrollTop = $('agent-scroll').scrollHeight; }
}
function bind() {
  $('nav').addEventListener('click', event => {
    const target = event.target.closest('[data-nav]');
    if (!target) return;
    app.view = target.dataset.nav;
    renderContent();
    if (app.view === 'agent') $('prompt').focus();
  });
  document.addEventListener('click', event => {
    const promptButton = event.target.closest('[data-prompt]');
    if (promptButton) sendPrompt(promptButton.dataset.prompt);
    if (event.target.closest('[data-focus-agent]')) { $('prompt').focus(); $('agent-panel').scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }
    const decision = event.target.closest('[data-decision]');
    if (decision) confirmAction(decision.dataset.decision === 'approve');
  });
  $('chat-form').addEventListener('submit', event => { event.preventDefault(); sendPrompt($('prompt').value); });
  $('prompt').addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); sendPrompt($('prompt').value); }
  });
  $('ask-agent-top').addEventListener('click', () => { $('prompt').focus(); $('agent-panel').scrollIntoView({ behavior: 'smooth', block: 'nearest' }); });
  $('theme-toggle').addEventListener('click', () => {
    const dark = document.documentElement.dataset.theme !== 'dark';
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    $('theme-toggle').innerHTML = `<svg><use href="#${dark ? 'i-sun' : 'i-moon'}"/></svg>`;
    $('theme-toggle').setAttribute('aria-label', dark ? '切换浅色主题' : '切换深色主题');
    try { localStorage.setItem('web-demo-theme', dark ? 'dark' : 'light'); } catch {}
  });
  const dialog = $('reset-dialog');
  $('reset-button').addEventListener('click', () => dialog.showModal());
  dialog.addEventListener('close', async () => {
    if (dialog.returnValue !== 'reset') return;
    if (app.busy) { showError('请等待当前请求结束，再重置沙盒。'); return; }
    try {
      await api('/api/reset', { method: 'POST', body: '{}' });
      app.pending = null; app.messages = [];
      await syncState(); showError('');
    } catch (err) { showError(`重置失败：${err.message}`); }
  });
}
async function boot() {
  bind();
  let preferDark = false;
  try { preferDark = localStorage.getItem('web-demo-theme') === 'dark'; } catch {}
  if (preferDark) {
    document.documentElement.dataset.theme = 'dark';
    $('theme-toggle').innerHTML = '<svg><use href="#i-sun"/></svg>';
    $('theme-toggle').setAttribute('aria-label', '切换浅色主题');
  }
  try { await syncState(); }
  catch (err) { $('content').innerHTML = `<p class="loading-card">Unable to load local demo backend. Start Node server first.</p>`; showError(err.message); }
}
boot();
