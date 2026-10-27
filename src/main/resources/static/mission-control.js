/* Mission Control — real-time multi-agent observability.
 * Live SSE stream from /api/ops/observability/stream drives a canvas constellation,
 * a request/response feed, and an agent-to-agent collaboration timeline. */
"use strict";

const $ = (sel) => document.querySelector(sel);

/* ── State ───────────────────────────────────────────────── */
const state = {
  nodes: new Map(),        // agentId -> {id, flow, mode, x, y, status, active, pulseAt, lastAt}
  edges: [],               // {from, to}
  particles: [],           // in-flight A2A message particles
  ripples: [],             // expanding rings on agent start
  filter: "all",
  paused: false,
  buffer: [],              // events received while paused
  feedCount: 0,
  inflight: new Map(),     // agent|case -> count (started - completed)
  latencies: [],           // recent durations
  eventTimes: [],          // for events/min
  a2aCount: 0,
  llmCount: 0,
  cases: new Set(),
  connected: false,
  lastSeq: 0,            // dedup guard across SSE replays/reconnects
};

const FEED_LIMIT = 140;
const INFLIGHT_STALE_MS = 120_000;
const STATUS_COLORS = {
  idle: "#3b4a6e", running: "#7dd3fc", pass: "#34d399",
  warn: "#fbbf24", fail: "#f87171", unavailable: "#7f8db3",
};
const FLOW_ORDER = ["loan", "incident", "grievance", "document", "platform"];

/* ── Helpers ─────────────────────────────────────────────── */
function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function clockTime(iso) {
  const d = iso ? new Date(iso) : new Date();
  return d.toLocaleTimeString([], { hour12: false }) + "." + String(d.getMilliseconds()).padStart(3, "0");
}

function toast(message) {
  const el = $("#toast");
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { el.hidden = true; }, 4200);
}

/* ── Topology & node layout ──────────────────────────────── */
function ensureNode(agentId, flow) {
  if (!agentId) return null;
  let node = state.nodes.get(agentId);
  if (!node) {
    node = { id: agentId, flow: flow || "platform", mode: "LLM", status: "idle", active: 0, pulseAt: 0, lastAt: 0 };
    state.nodes.set(agentId, node);
    layoutDirty = true;
  }
  return node;
}

async function loadTopology() {
  try {
    const res = await fetch("/api/ops/observability/topology", { credentials: "same-origin" });
    if (!res.ok) throw new Error("topology " + res.status);
    const topo = await res.json();
    if (topo.modelMode === "SCRIPTED" && new URLSearchParams(location.search).get("showcase") !== "1") {
      const params = new URLSearchParams(location.search);
      params.set("showcase", "1");
      location.replace(`${location.pathname}?${params}`);
      return;
    }
    for (const n of topo.nodes || []) {
      const node = ensureNode(n.id, n.flow);
      if (node) node.mode = n.mode;
    }
    state.edges = (topo.edges || []).filter((e) => state.nodes.has(e.from) && state.nodes.has(e.to));
    layoutDirty = true;
  } catch (e) {
    console.warn("topology unavailable, nodes will be learned from the stream", e);
  }
}

let layoutDirty = true;
function layout(canvas) {
  const w = canvas.clientWidth, h = canvas.clientHeight;
  const flows = FLOW_ORDER.filter((f) => [...state.nodes.values()].some((n) => n.flow === f));
  if (flows.length === 0) return;
  const band = w / flows.length;
  flows.forEach((flow, fi) => {
    const members = [...state.nodes.values()].filter((n) => n.flow === flow);
    const cx = band * fi + band / 2;
    const cy = h / 2;
    const radius = Math.max(60, Math.min(band, h) / 2 - 74);
    members.forEach((n, i) => {
      const angle = -Math.PI / 2 + (i / members.length) * Math.PI * 2;
      n.x = cx + Math.cos(angle) * radius;
      n.y = cy + Math.sin(angle) * (radius * 0.78);
    });
  });
}

/* ── Canvas render loop ──────────────────────────────────── */
const canvas = $("#constellation");
const ctx = canvas.getContext("2d");

function resize() {
  const dpr = window.devicePixelRatio || 1;
  canvas.width = canvas.clientWidth * dpr;
  canvas.height = canvas.clientHeight * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  layoutDirty = true;
}
window.addEventListener("resize", resize);

function drawEdge(e) {
  const a = state.nodes.get(e.from), b = state.nodes.get(e.to);
  if (!a || !b) return;
  ctx.strokeStyle = "rgba(70, 90, 140, 0.25)";
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(a.x, a.y);
  const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2 - 24;
  ctx.quadraticCurveTo(mx, my, b.x, b.y);
  ctx.stroke();
}

function drawNode(n, now) {
  const active = n.active > 0;
  const color = active ? STATUS_COLORS.running : (STATUS_COLORS[n.status] || STATUS_COLORS.idle);

  // start ripple
  if (n.pulseAt) {
    const t = (now - n.pulseAt) / 900;
    if (t < 1) {
      ctx.strokeStyle = `rgba(125, 211, 252, ${0.5 * (1 - t)})`;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(n.x, n.y, 16 + t * 34, 0, Math.PI * 2);
      ctx.stroke();
    } else n.pulseAt = 0;
  }

  // glow + body
  const radius = active ? 15 + Math.sin(now / 180) * 2 : 13;
  const grad = ctx.createRadialGradient(n.x, n.y, 2, n.x, n.y, radius * 2.4);
  grad.addColorStop(0, color + "55");
  grad.addColorStop(1, "transparent");
  ctx.fillStyle = grad;
  ctx.beginPath();
  ctx.arc(n.x, n.y, radius * 2.4, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = "#0c1220";
  ctx.strokeStyle = color;
  ctx.lineWidth = active ? 2.5 : 1.6;
  ctx.beginPath();
  ctx.arc(n.x, n.y, radius, 0, Math.PI * 2);
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(n.x, n.y, 4, 0, Math.PI * 2);
  ctx.fill();

  // label
  ctx.font = "600 10.5px 'SF Mono', Menlo, monospace";
  ctx.textAlign = "center";
  ctx.fillStyle = active ? "#e6f0ff" : "#8fa1c7";
  ctx.fillText(n.id, n.x, n.y + radius + 15);

  // active spinner arc
  if (active) {
    ctx.strokeStyle = "#7dd3fc";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(n.x, n.y, radius + 5, now / 260, now / 260 + Math.PI * 0.9);
    ctx.stroke();
  }
}

function drawParticles(now, dt) {
  state.particles = state.particles.filter((p) => {
    p.t += dt / p.duration;
    const a = state.nodes.get(p.from), b = state.nodes.get(p.to);
    if (!a || !b || p.t >= 1) {
      if (b && p.t >= 1) b.pulseAt = now;
      return p.t < 1;
    }
    const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2 - 30;
    const t = p.t;
    const x = (1 - t) * (1 - t) * a.x + 2 * (1 - t) * t * mx + t * t * b.x;
    const y = (1 - t) * (1 - t) * a.y + 2 * (1 - t) * t * my + t * t * b.y;

    ctx.strokeStyle = "rgba(251, 191, 36, 0.35)";
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.quadraticCurveTo(mx, my, b.x, b.y);
    ctx.stroke();

    ctx.fillStyle = "#fbbf24";
    ctx.shadowColor = "#fbbf24";
    ctx.shadowBlur = 12;
    ctx.beginPath();
    ctx.arc(x, y, 4, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

    ctx.font = "700 9px 'SF Mono', Menlo, monospace";
    ctx.textAlign = "center";
    ctx.fillStyle = "rgba(251, 191, 36, 0.9)";
    ctx.fillText(p.type, x, y - 10);
    return true;
  });
}

let lastFrame = performance.now();
function frame(now) {
  const dt = Math.min(80, now - lastFrame);
  lastFrame = now;
  if (layoutDirty) { layout(canvas); layoutDirty = false; }
  ctx.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
  for (const e of state.edges) drawEdge(e);
  drawParticles(now, dt);
  for (const n of state.nodes.values()) drawNode(n, now);
  requestAnimationFrame(frame);
}

/* ── Feed rendering ──────────────────────────────────────── */
const KIND_ICONS = { AGENT_STARTED: "⚡", AGENT_COMPLETED: "✓", FINDING: "◈", MESSAGE: "⇄", FLOW: "❖", LLM: "⌬" };

function matchesFilter(ev) {
  if (state.filter === "all") return true;
  if (state.filter === "AGENT") return ev.kind === "AGENT_STARTED" || ev.kind === "AGENT_COMPLETED";
  return ev.kind === state.filter;
}

function feedCard(ev) {
  const el = document.createElement("div");
  el.className = `ev ev-kind-${ev.kind}`;
  const bad = ["FAIL", "ERROR", "TIMEOUT"].includes(ev.status) || /FAIL|ERROR|TIMEOUT/i.test(ev.label || "");
  if (bad) el.classList.add("is-bad");
  const badge = ev.kind === "AGENT_STARTED"
    ? `<span class="ev-badge ev-badge-run">RUNNING</span>`
    : ev.status ? `<span class="ev-badge ev-badge-${escapeHtml(ev.status)}">${escapeHtml(ev.status)}</span>` : "";
  const dur = ev.durationMs != null ? `<span class="ev-dur">${ev.durationMs}ms</span>` : "";
  const route = ev.kind === "MESSAGE" ? `${escapeHtml(ev.agent)} → ${escapeHtml(ev.to || "*")}` : escapeHtml(ev.agent || ev.flow);
  el.innerHTML = `
    <div class="ev-icon">${KIND_ICONS[ev.kind] || "•"}</div>
    <div class="ev-main">
      <div class="ev-title"><span class="ev-flow ev-flow-${escapeHtml(ev.flow || "platform")}">${escapeHtml(ev.flow || "")}</span><span class="ev-agent">${route}</span><span class="ev-label">${escapeHtml(ev.label || "")}</span></div>
      ${ev.detail ? `<p class="ev-detail">${escapeHtml(ev.detail)}</p>` : ""}
    </div>
    <div class="ev-meta">${badge}${dur}<span class="ev-time">${clockTime(ev.at)}</span></div>`;
  return el;
}

function pushFeed(ev) {
  if (!matchesFilter(ev)) return;
  const feed = $("#feed");
  feed.prepend(feedCard(ev));
  while (feed.children.length > FEED_LIMIT) feed.lastChild.remove();
}

/* ── LLM inspector ───────────────────────────────────────── */
function pushLlm(ev) {
  if (ev.kind !== "LLM") return;
  $("#llm-empty")?.remove();
  const lane = $("#llm-feed");
  const el = document.createElement("details");
  const failed = ev.status === "ERROR";
  const provider = (ev.label || "").startsWith("provider · ");
  el.className = "llm" + (failed ? " is-error" : "");
  el.innerHTML = `
    <summary>
      <span class="llm-icon">⌬</span>
      <span class="llm-head">
        <span class="llm-title"><span class="llm-agent">${escapeHtml(ev.agent)}</span><span class="llm-model">${escapeHtml(ev.label || "")}</span></span>
        <span class="llm-sub">${escapeHtml(ev.detail || (failed ? "call failed" : ""))}</span>
      </span>
      <span class="llm-side">
        ${ev.durationMs != null ? `<span class="ev-dur">${ev.durationMs}ms</span>` : ""}
        <span class="ev-time">${clockTime(ev.at)}</span>
      </span>
    </summary>
    <div class="llm-body">
      ${ev.request ? `<div><div class="llm-block-label req">${provider ? "→ actual provider request (JSON)" : "→ agent prompt (system + user)"}</div><pre>${escapeHtml(ev.request)}</pre></div>` : ""}
      ${provider ? `<div class="llm-sub">Provider JSON includes any returned reasoning fields, final content, finish reason and usage. Missing reasoning fields mean the provider did not return them. Payloads may be truncated.</div>` : ""}
      ${ev.response ? `<div><div class="llm-block-label res">${provider ? "← actual provider response (JSON)" : "← parsed agent result"}</div><pre>${escapeHtml(ev.response)}</pre></div>` : ""}
      ${failed && ev.detail ? `<div><div class="llm-block-label" style="color:var(--fail)">error</div><pre>${escapeHtml(ev.detail)}</pre></div>` : ""}
    </div>`;
  lane.prepend(el);
  while (lane.children.length > 50) lane.lastChild.remove();
}

/* ── A2A timeline ────────────────────────────────────────── */
function pushA2A(ev) {
  if (ev.kind !== "MESSAGE") return;
  $("#a2a-empty")?.remove();
  const lane = $("#a2a-feed");
  const el = document.createElement("div");
  el.className = "msg";
  el.innerHTML = `
    <span class="msg-party">${escapeHtml(ev.agent)}<span class="ev-flow ev-flow-${escapeHtml(ev.flow || "platform")}" style="margin-left:6px">${escapeHtml(ev.flow || "")}</span></span>
    <span class="msg-to">➤</span>
    <span class="msg-party">${escapeHtml(ev.to || "case board")}</span>
    <span class="msg-body">
      <span class="msg-type msg-type-${escapeHtml(ev.label)}">${escapeHtml(ev.label)}</span>
      <span class="msg-detail">${escapeHtml(ev.detail || "")}</span>
      <span class="ev-time">${clockTime(ev.at)}</span>
    </span>`;
  lane.prepend(el);
  while (lane.children.length > 60) lane.lastChild.remove();
}

/* ── Stats ───────────────────────────────────────────────── */
function bumpStat(id, value) {
  const el = $(id);
  if (el.textContent !== String(value)) {
    el.textContent = value;
    el.classList.remove("flash");
    void el.offsetWidth;
    el.classList.add("flash");
  }
}

function refreshStats() {
  const now = Date.now();
  state.eventTimes = state.eventTimes.filter((t) => now - t < 60_000);
  pruneStaleInflight(now);
  syncNodeActivity();
  const active = [...state.inflight.values()].reduce((a, rec) => a + rec.count, 0);
  const lat = state.latencies.length
    ? Math.round(state.latencies.reduce((a, b) => a + b, 0) / state.latencies.length) : null;
  bumpStat("#stat-active", active);
  bumpStat("#stat-rate", state.eventTimes.length);
  bumpStat("#stat-latency", lat == null ? "—" : lat + "ms");
  bumpStat("#stat-a2a", state.a2aCount);
  bumpStat("#stat-llm", state.llmCount);
  bumpStat("#stat-cases", state.cases.size);
}
setInterval(refreshStats, 1500);

function inflightKey(agent, caseId) {
  return `${agent || "unknown"}|${caseId || "unknown"}`;
}

function inflightTime(ev) {
  if (!ev?.at) return Date.now();
  const ms = Date.parse(ev.at);
  return Number.isFinite(ms) ? ms : Date.now();
}

function markStarted(ev) {
  const key = inflightKey(ev.agent, ev.caseId);
  const prev = state.inflight.get(key);
  state.inflight.set(key, { count: (prev?.count || 0) + 1, at: inflightTime(ev), agent: ev.agent });
}

function markCompleted(ev) {
  const key = inflightKey(ev.agent, ev.caseId);
  const prev = state.inflight.get(key);
  const next = Math.max(0, (prev?.count || 1) - 1);
  if (next === 0) state.inflight.delete(key);
  else state.inflight.set(key, { count: next, at: inflightTime(ev), agent: ev.agent });
}

function clearCaseInflight(subjectId) {
  if (!subjectId) return;
  for (const [key] of state.inflight) {
    if (key.endsWith(`|${subjectId}`)) state.inflight.delete(key);
  }
}

function pruneStaleInflight(now = Date.now()) {
  for (const [key, rec] of state.inflight) {
    if (now - (rec.at || now) > INFLIGHT_STALE_MS) state.inflight.delete(key);
  }
}

function syncNodeActivity() {
  for (const n of state.nodes.values()) n.active = 0;
  for (const rec of state.inflight.values()) {
    if (!rec?.agent || rec.count <= 0) continue;
    const n = ensureNode(rec.agent, "platform");
    if (n) n.active += rec.count;
  }
}

/* ── Event application ───────────────────────────────────── */
function applyEvent(ev, { live = true } = {}) {
  if (typeof ev.seq === "number") {
    if (ev.seq <= state.lastSeq) return; // replay/reconnect duplicate
    state.lastSeq = ev.seq;
  }
  state.eventTimes.push(Date.now());
  if (ev.caseId) state.cases.add(ev.caseId);

  if (ev.kind === "AGENT_STARTED") {
    const n = ensureNode(ev.agent, ev.flow);
    markStarted(ev);
    n.pulseAt = performance.now(); n.lastAt = Date.now();
    caption(`${ev.flow}/${ev.agent} started on case ${shortId(ev.caseId)}`);
  } else if (ev.kind === "AGENT_COMPLETED") {
    const n = ensureNode(ev.agent, ev.flow);
    markCompleted(ev);
    const label = (ev.label || "").toUpperCase();
    n.status = label.includes("FAIL") || ev.status === "ERROR" || ev.status === "TIMEOUT" ? "fail"
      : label.includes("WARN") ? "warn"
      : label.includes("UNAVAILABLE") ? "unavailable" : "pass";
    if (ev.durationMs != null) {
      state.latencies.push(ev.durationMs);
      if (state.latencies.length > 60) state.latencies.shift();
    }
    caption(`${ev.flow}/${ev.agent} → ${ev.label} (${ev.durationMs ?? "?"}ms)`);
  } else if (ev.kind === "MESSAGE") {
    state.a2aCount++;
    ensureNode(ev.agent, ev.flow);
    ensureNode(ev.to, ev.flow);
    if (live && ev.agent && ev.to && state.nodes.has(ev.agent) && state.nodes.has(ev.to)) {
      state.particles.push({ from: ev.agent, to: ev.to, t: 0, duration: 1400, type: ev.label });
      if (state.particles.length > 24) state.particles.shift();
    }
    caption(`A2A ${ev.agent} → ${ev.to}: ${ev.label}`);
    pushA2A(ev);
  } else if (ev.kind === "LLM") {
    state.llmCount++;
    ensureNode(ev.agent, ev.flow);
    caption(`LLM ${ev.agent} → ${ev.label}${ev.durationMs != null ? ` (${ev.durationMs}ms)` : ""}`);
    pushLlm(ev);
  } else if (ev.kind === "FINDING") {
    ensureNode(ev.agent, ev.flow);
    caption(`${ev.agent} published ${ev.label}`);
  } else if (ev.kind === "FLOW") {
    if (ev.label && /\\.case\\.completed$/.test(ev.label) && ev.detail) {
      const m = ev.detail.match(/subject\\s+([^\\s]+)/i);
      if (m) clearCaseInflight(m[1]);
    }
    caption(`flow: ${ev.label} ${ev.detail ? "— " + ev.detail : ""}`);
  }

  if (state.paused) state.buffer.push(ev);
  else pushFeed(ev);
  refreshStats();
}

function shortId(id) {
  return id && id.length > 10 ? id.slice(0, 8) + "…" : id;
}

function caption(text) {
  $("#canvas-caption").textContent = text;
}

/* ── SSE connection ──────────────────────────────────────── */
function setConnection(connected) {
  state.connected = connected;
  const box = $("#connection");
  box.classList.toggle("is-live", connected);
  box.classList.toggle("is-down", !connected);
  $("#connection-label").textContent = connected ? "live" : "reconnecting";
}

function connect() {
  const source = new EventSource("/api/ops/observability/stream");
  source.addEventListener("activity", (e) => {
    try { applyEvent(JSON.parse(e.data)); } catch (err) { console.error("bad event", err); }
  });
  source.onopen = () => setConnection(true);
  source.onerror = () => setConnection(false); // EventSource auto-reconnects
}

/* ── Controls ────────────────────────────────────────────── */
document.querySelectorAll(".mc-filters .mc-chip").forEach((chip) => {
  chip.addEventListener("click", () => {
    document.querySelectorAll(".mc-filters .mc-chip").forEach((c) => c.classList.remove("is-active"));
    chip.classList.add("is-active");
    state.filter = chip.dataset.filter;
  });
});

$("#pause-feed").addEventListener("click", () => {
  state.paused = !state.paused;
  const btn = $("#pause-feed");
  btn.setAttribute("aria-pressed", String(state.paused));
  btn.textContent = state.paused ? "▶ resume" : "⏸ pause";
  if (!state.paused) {
    for (const ev of state.buffer.splice(0)) pushFeed(ev);
  }
});

$("#launch-demo").addEventListener("click", async (btn) => {
  if (new URLSearchParams(location.search).get("showcase") === "1") {
    if (window.launchSyntheticInvestigation) await window.launchSyntheticInvestigation();
    return;
  }
  const button = btn.currentTarget;
  button.disabled = true;
  try {
    const sample = await (await fetch("/api/loans/sample", { credentials: "same-origin" })).json();
    const jitter = (v, spread) => Math.round(Number(v) * (1 + (Math.random() - 0.5) * spread));
    const app = {
      ...sample,
      applicantId: sample.applicantId + "-" + Math.random().toString(36).slice(2, 6).toUpperCase(),
      requestedAmount: jitter(sample.requestedAmount, 0.6),
      annualIncome: jitter(sample.annualIncome, 0.4),
    };
    toast(`Demo scenario launched for ${app.applicantId} — watch the constellation.`);
    const res = await fetch("/api/loans/evaluate", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify(app),
    });
    const body = await res.json().catch(() => ({}));
    toast(res.ok
      ? `Scenario complete: ${body.decision || res.status}`
      : `Scenario ended with HTTP ${res.status}`);
  } catch (e) {
    toast("Demo launch failed: " + e.message);
  } finally {
    button.disabled = false;
  }
});

/* ── Boot ────────────────────────────────────────────────── */
(async function boot() {
  resize();
  await loadTopology();
  // The SSE stream replays the recent backlog on connect, so no separate /recent fetch here.
  connect();
  requestAnimationFrame(frame);
})();
