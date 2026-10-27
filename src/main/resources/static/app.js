const FLOW_META = {
  loan: { label: "Loan review", short: "Loan", icon: "L" },
  incident: { label: "Incident response", short: "Incident", icon: "I" },
  grievance: { label: "Grievance redress", short: "Grievance", icon: "G" },
  document: { label: "Document intelligence", short: "Document", icon: "D" }
};

const FLOW_FIELDS = {
  loan: "",
  incident: `
    <label class="field-label" for="incidentId">Incident reference <span class="required">*</span></label><input class="input-control" id="incidentId" name="incidentId" required maxlength="64" autocomplete="off">
    <label class="field-label" for="incidentTitle">Title <span class="required">*</span></label><input class="input-control" id="incidentTitle" name="title" required maxlength="200">
    <label class="field-label" for="description">What happened? <span class="required">*</span></label><textarea class="input-control" id="description" name="description" rows="4" required maxlength="20000"></textarea>
    <div class="field-row">
      <div><label class="field-label" for="service">Affected service <span class="required">*</span></label><input class="input-control" id="service" name="service" required maxlength="200"></div>
      <div><label class="field-label" for="environment">Environment</label><input class="input-control" id="environment" name="environment" maxlength="100"></div>
    </div>
    <div class="field-row">
      <div><label class="field-label" for="reporter">Reporter</label><input class="input-control" id="reporter" name="reporter" maxlength="120"></div>
      <div><label class="field-label" for="tags">Tags</label><input class="input-control" id="tags" name="tags" placeholder="Separate tags with commas" maxlength="300"></div>
    </div>`,
  grievance: `
    <div class="safety-note"><strong>Privacy first.</strong> Do not enter real survivor, worker, or customer information here. Sensitive reports are restricted by policy; this UI is a local development prototype.</div>
    <div class="field-row">
      <div><label class="field-label" for="grievanceId">Case reference <span class="required">*</span></label><input class="input-control" id="grievanceId" name="grievanceId" required maxlength="64"></div>
      <div><label class="field-label" for="projectId">Project reference <span class="required">*</span></label><input class="input-control" id="projectId" name="projectId" required maxlength="64"></div>
    </div>
    <label class="field-label" for="description">Report summary <span class="required">*</span></label><textarea class="input-control" id="description" name="description" rows="4" required maxlength="20000"></textarea>
    <div class="field-row">
      <div><label class="field-label" for="channel">Received through</label><select class="input-control" id="channel" name="channel"><option value="WEB">Web</option><option value="PHONE">Phone</option><option value="SMS">SMS</option><option value="COMMUNITY_MEETING">Community meeting</option><option value="LETTER">Letter</option></select></div>
      <div><label class="field-label" for="location">Location</label><input class="input-control" id="location" name="location" maxlength="200"></div>
    </div>
    <label class="field-label"><input type="checkbox" name="anonymous" value="true"> Submit anonymously</label>`,
  document: `
    <div class="field-row">
      <div><label class="field-label" for="documentId">Document reference <span class="required">*</span></label><input class="input-control" id="documentId" name="documentId" required maxlength="64"></div>
      <div><label class="field-label" for="documentTitle">Document title</label><input class="input-control" id="documentTitle" name="title" maxlength="200"></div>
    </div>
    <label class="field-label" for="content">Extracted text <span class="required">*</span></label><textarea class="input-control" id="content" name="content" rows="7" required maxlength="100000" aria-describedby="document-help"></textarea>
    <p class="field-help" id="document-help">Text is sent to the configured backend for analysis. Use non-sensitive synthetic content only.</p>`
};

const state = {
  view: "overview",
  cases: [],
  approvals: [],
  caseError: "",
  approvalError: "",
  nextCursor: null,
  filterFlow: "all",
  filterStatus: "all",
  search: "",
  selectedCaseId: "",
  selectedApproval: null,
  toastTimer: 0,
  pollTimer: 0
};
const loanWizard = { profile: null, step: "find", details: null };
let pendingSubmission = null;
let submissionInFlight = false;
let decisionInFlight = false;
let caseRefreshInFlight = false;

const $ = (selector, root = document) => root.querySelector(selector);
const content = $("#content");
const caseDialog = $("#case-dialog");
const decisionDialog = $("#decision-dialog");

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[char]);
}

function prettyStatus(status) {
  return String(status || "UNKNOWN").replaceAll("_", " ").toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase());
}

function statusClass(status) {
  return String(status || "").toLowerCase().replace(/[^a-z0-9_-]/g, "");
}

function formatDate(value, options = { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat(undefined, options).format(date);
}

function shortId(value) {
  const id = String(value || "");
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-5)}` : id || "—";
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("Accept", "application/json");
  if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const response = await fetch(path, { ...options, headers, credentials: "same-origin",
    signal: AbortSignal.timeout(20000) });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const body = await response.json();
      message = body.detail || body.message || message;
    } catch {
      // Keep the HTTP status as the useful error when the response is not JSON.
    }
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function announce(message) {
  const toast = $("#toast");
  toast.textContent = message;
  toast.classList.add("visible");
  window.clearTimeout(state.toastTimer);
  state.toastTimer = window.setTimeout(() => toast.classList.remove("visible"), 3600);
}

function showGlobalAlert(message) {
  const alert = $("#global-alert");
  alert.textContent = message;
  alert.hidden = !message;
}

function setConnection(ok, label) {
  const indicator = $("#connection-indicator");
  indicator.classList.toggle("connected", ok);
  indicator.classList.toggle("disconnected", !ok);
  $("#connection-title").textContent = ok ? "API connected" : "API unavailable";
  $("#connection-copy").textContent = label;
}

async function loadData() {
  const [casesResult, approvalsResult] = await Promise.allSettled([
    api("/api/v1/cases?limit=50"),
    api("/api/approvals?status=PENDING")
  ]);
  state.caseError = "";
  state.approvalError = "";
  if (casesResult.status === "fulfilled") {
    state.cases = Array.isArray(casesResult.value?.items) ? casesResult.value.items : [];
    state.nextCursor = casesResult.value?.nextCursor || null;
  } else {
    state.caseError = casesResult.reason.message;
    state.cases = [];
  }
  if (approvalsResult.status === "fulfilled") {
    state.approvals = Array.isArray(approvalsResult.value) ? approvalsResult.value : [];
  } else {
    state.approvalError = approvalsResult.reason.message;
    state.approvals = [];
  }
  const connected = casesResult.status === "fulfilled" || approvalsResult.status === "fulfilled";
  setConnection(connected, connected ? "Live API data" : "Check API / access");
  $("#case-nav-count").textContent = casesResult.status === "fulfilled" ? String(state.cases.length) : "!";
  $("#approval-nav-count").textContent = approvalsResult.status === "fulfilled" ? String(state.approvals.length) : "!";
  if (!connected && (casesResult.reason?.status === 401 || approvalsResult.reason?.status === 401)) {
    showGlobalAlert("The API requires an authenticated caller. Sign-in is not connected to this development UI; configure the backend for local development or complete the approved identity integration.");
  } else if (!connected && (casesResult.reason?.status === 403 || approvalsResult.reason?.status === 403)) {
    showGlobalAlert("The current caller does not have permission to view these records. Access is enforced by the backend; this UI cannot grant access.");
  } else if (!connected) {
    showGlobalAlert("The backend could not be reached. Start the Spring Boot application, then refresh the workspace.");
  } else {
    showGlobalAlert("");
  }
  $("#content").setAttribute("aria-busy", "false");
  if (state.view === "case-detail" && state.selectedCaseId) await refreshSelectedCase();
  else render();
}

function iconMetric(kind) {
  const icons = {
    cases: '<svg viewBox="0 0 24 24"><path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h4l2 2h5A2.5 2.5 0 0 1 20 8.5v9a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 17.5v-11Z"/><path d="M8 12h8M8 15.5h5"/></svg>',
    review: '<svg viewBox="0 0 24 24"><path d="M12 3 20 6v5c0 5-3.4 8.4-8 10-4.6-1.6-8-5-8-10V6l8-3Z"/><path d="m8.5 12 2.2 2.2 4.8-5"/></svg>',
    done: '<svg viewBox="0 0 24 24"><path d="M5 12.5 9.5 17 19 7.5"/><circle cx="12" cy="12" r="9"/></svg>',
    active: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.5 2"/></svg>'
  };
  return icons[kind] || icons.cases;
}

function metricCard(label, value, note, icon, tone = "") {
  return `<article class="metric-card"><div class="metric-top"><span>${escapeHtml(label)}</span><span class="metric-icon ${tone}">${iconMetric(icon)}</span></div><div class="metric-value">${escapeHtml(value)}</div><div class="metric-note">${escapeHtml(note)}</div></article>`;
}

function caseRows(items, compact = false) {
  if (!items.length) {
    return `<tr><td colspan="5"><div class="empty-state"><span class="empty-icon" aria-hidden="true">⌁</span><strong>No cases to show yet</strong><p>New work will appear here as soon as it is submitted through a connected workflow.</p></div></td></tr>`;
  }
  return items.map(row => {
    const meta = FLOW_META[row.flow] || { label: row.flow || "Workflow", short: row.flow || "Case", icon: "•" };
    const title = row.result?.title || row.result?.summary || row.caseId;
    const titleText = compact ? `Case ${shortId(row.caseId)}` : title;
    return `<tr>
      <td><div class="case-title"><button data-case-id="${escapeHtml(row.caseId)}">${escapeHtml(titleText)}</button><small>${escapeHtml(shortId(row.caseId))}</small></div></td>
      <td><span class="flow-chip"><span class="flow-symbol">${escapeHtml(meta.icon)}</span>${escapeHtml(meta.short)}</span></td>
      <td><span class="status-pill ${statusClass(row.status)}">${escapeHtml(prettyStatus(row.status))}</span></td>
      <td>${escapeHtml(formatDate(row.updatedAt || row.createdAt))}</td>
      <td><button class="case-open-button" data-case-id="${escapeHtml(row.caseId)}">Open</button></td>
    </tr>`;
  }).join("");
}

function caseTable(items, compact = false) {
  return `<div class="table-wrap"><table class="case-table"><thead><tr><th>Case</th><th>Workflow</th><th>Status</th><th>Updated</th><th></th></tr></thead><tbody>${caseRows(items, compact)}</tbody></table></div>`;
}

function pageHeading(eyebrow, title, subtitle, action = "") {
  return `<div class="page-heading"><div><div class="eyebrow">${escapeHtml(eyebrow)}</div><h1>${escapeHtml(title)}</h1><p>${escapeHtml(subtitle)}</p></div>${action ? `<div class="heading-actions">${action}</div>` : ""}</div>`;
}

function newCaseButton(text = "Start a new case") {
  return `<button class="button button-primary" data-new-case><span>${escapeHtml(text)}</span><span aria-hidden="true">↗</span></button>`;
}

function overviewView() {
  const total = state.cases.length;
  const pending = state.cases.filter(row => ["OPEN", "QUEUED", "ACCEPTED", "PENDING", "RUNNING", "IN_PROGRESS"].includes(String(row.status).toUpperCase())).length;
  const completed = state.cases.filter(row => ["COMPLETED", "SUCCEEDED", "APPROVED", "REJECTED"].includes(String(row.status).toUpperCase())).length;
  const needsReview = state.approvals.length;
  const latest = [...state.cases].sort((a, b) => new Date(b.updatedAt || b.createdAt) - new Date(a.updatedAt || a.createdAt)).slice(0, 5);
  const casesBody = state.caseError
    ? `<div class="inline-error">Could not load cases: ${escapeHtml(state.caseError)}. The API error is shown as-is; no sample data is substituted.</div>`
    : caseTable(latest, true);
  const approvalNote = state.approvalError
    ? `<p class="inline-error">Review queue unavailable: ${escapeHtml(state.approvalError)}</p>`
    : `<button class="quick-action" data-view="approvals"><span class="quick-action-icon amber">◈</span><span class="quick-action-copy"><strong>${needsReview ? `${needsReview} item${needsReview === 1 ? "" : "s"} need review` : "Review queue is clear"}</strong><small>${needsReview ? "Open the authorized review queue" : "Pending items appear here"}</small></span><span class="quick-action-arrow">›</span></button>`;
  const now = new Date();
  const greeting = now.getHours() < 12 ? "Good morning" : now.getHours() < 18 ? "Good afternoon" : "Good evening";
  return `${pageHeading("OPERATIONS / OVERVIEW", `${greeting}, team`, "A clear view of work moving across your banking operations.", newCaseButton())}
    <section class="hero-panel" aria-label="Workspace introduction">
      <div class="hero-copy"><div class="eyebrow">YOUR OPERATIONS, IN FOCUS</div><h2>Better decisions begin with a clearer picture.</h2><p>Track workflow progress, find the evidence behind outcomes, and keep people at the center of every important decision.</p></div>
      <div class="hero-art" aria-hidden="true"><span class="hero-orbit"></span><span class="hero-star"><svg viewBox="0 0 36 36"><path d="M18 2.5 21.9 14l11.6 4-11.6 3.9L18 33.5 14.1 22 2.5 18l11.6-4L18 2.5Z"/><circle cx="18" cy="18" r="3.1"/></svg></span><i class="hero-spark one"></i><i class="hero-spark two"></i></div>
    </section>
    <section class="workflow-launcher" aria-label="Choose a demo workflow">
      ${Object.entries(FLOW_META).map(([flow, meta]) => `<button class="workflow-tile" data-start-flow="${flow}"><strong>${escapeHtml(meta.label)}</strong><span>${{
        loan: "Find a customer and request a loan.",
        incident: "Investigate a service problem.",
        grievance: "Route a concern to the right team.",
        document: "Extract facts from document text."
      }[flow]}</span><small>Start guided case →</small></button>`).join("")}
    </section>
    <section class="metric-grid" aria-label="Current workspace summary">
      ${metricCard("Cases in workspace", state.caseError ? "—" : total, state.caseError ? "Live data unavailable" : "Visible to your current caller", "cases", "blue")}
      ${metricCard("In progress", state.caseError ? "—" : pending, state.caseError ? "Live data unavailable" : "Accepted or currently running", "active", "amber")}
      ${metricCard("Awaiting review", state.approvalError ? "—" : needsReview, state.approvalError ? "Review data unavailable" : "Backend approval queue", "review", "teal")}
      ${metricCard("Completed", state.caseError ? "—" : completed, state.caseError ? "Live data unavailable" : "Completed or decided cases", "done", "slate")}
    </section>
    <section class="dashboard-grid">
      <article class="panel"><div class="panel-head"><div><h2>Recent cases</h2><p>Latest work visible to your caller</p></div><button class="text-button" data-view="cases">View all cases&nbsp; →</button></div>${casesBody}</article>
      <aside class="panel quick-panel"><div class="panel-head"><div><h2>Quick actions</h2><p>Move work forward</p></div></div>
        <button class="quick-action" data-new-case><span class="quick-action-icon">＋</span><span class="quick-action-copy"><strong>Create a new case</strong><small>Start one of four workflows</small></span><span class="quick-action-arrow">›</span></button>
        ${approvalNote}
        <div class="trust-note"><strong>Human-led by design.</strong> AI findings support decisions; policy and authorized people remain in control. Live case data comes from the backend—this page does not invent sample records.</div>
      </aside>
    </section>`;
}

function filteredCases() {
  const query = state.search.trim().toLowerCase();
  return state.cases.filter(row => {
    const flowMatch = state.filterFlow === "all" || row.flow === state.filterFlow;
    const statusMatch = state.filterStatus === "all" || String(row.status).toLowerCase() === state.filterStatus;
    const text = `${row.caseId} ${row.flow} ${row.status} ${JSON.stringify(row.result || {})}`.toLowerCase();
    return flowMatch && statusMatch && (!query || text.includes(query));
  });
}

function casesView() {
  const rows = state.caseError
    ? `<div class="inline-error">Could not load cases: ${escapeHtml(state.caseError)}.</div>`
    : caseTable(filteredCases());
  return `${pageHeading("OPERATIONS / CASES", "Case workspace", "Follow each case from intake through its latest server-confirmed state.", newCaseButton("Create case"))}
    <div class="toolbar">
      <label class="search-box"><svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.5 4.5"/></svg><input id="case-search" type="search" placeholder="Search case reference or workflow" aria-label="Search cases" value="${escapeHtml(state.search)}"></label>
      <select id="flow-filter" class="filter-select" aria-label="Filter by workflow"><option value="all">All workflows</option>${Object.entries(FLOW_META).map(([key, meta]) => `<option value="${key}" ${state.filterFlow === key ? "selected" : ""}>${escapeHtml(meta.label)}</option>`).join("")}</select>
      <select id="status-filter" class="filter-select" aria-label="Filter by status"><option value="all">All statuses</option>${["OPEN", "RUNNING", "PENDING_APPROVAL", "COMPLETED", "APPROVED", "REJECTED", "BLOCKED", "FAILED", "EXECUTION_FAILED", "EXPIRED"].map(status => `<option value="${status.toLowerCase()}" ${state.filterStatus === status.toLowerCase() ? "selected" : ""}>${escapeHtml(prettyStatus(status))}</option>`).join("")}</select>
    </div>
    <article class="panel case-list-panel"><div class="panel-head"><div><h2>Loaded cases</h2><p>${state.caseError ? "Live data unavailable" : `${filteredCases().length} matching records · filters search loaded pages`}</p></div>${state.nextCursor ? '<button class="button button-secondary" id="load-more-cases">Load more cases</button>' : ""}</div>${rows}</article>`;
}

async function loadMoreCases(button) {
  if (!state.nextCursor) return;
  button.disabled = true;
  try {
    const page = await api(`/api/v1/cases?limit=50&cursor=${encodeURIComponent(state.nextCursor)}`);
    for (const row of page.items) if (!state.cases.some(item => item.caseId === row.caseId)) state.cases.push(row);
    state.nextCursor = page.nextCursor || null;
    render();
  } catch (error) { announce(`Could not load the next page: ${error.message}`); }
  finally { button.disabled = false; }
}

function readableResult(row) {
  const r = row.result;
  if (!r || typeof r !== "object") return "<p>No outcome has been recorded yet. You do not need to submit again.</p>";
  const list = (title, values) => Array.isArray(values) && values.length
    ? `<h3>${escapeHtml(title)}</h3><ul>${values.map(value => `<li>${escapeHtml(typeof value === "string" ? value : JSON.stringify(value))}</li>`).join("")}</ul>` : "";
  const fact = (label, value) => value !== null && value !== undefined && value !== ""
    ? `<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>` : "";
  const money = value => new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" }).format(value);
  let html = `<h3>${escapeHtml(prettyStatus(r.decision || r.status || "Result recorded"))}</h3>`;
  html += r.reasoning || r.narrative || r.analysis?.summary
    ? `<p>${escapeHtml(r.reasoning || r.narrative || r.analysis.summary)}</p>` : "";
  if (row.flow === "loan") {
    html += `<dl class="profile-facts">${fact("Approved amount", r.approvedAmount == null ? null : money(r.approvedAmount))}${fact("Annual interest rate (APR)", r.interestRateApr == null ? null : `${r.interestRateApr}%`)}${fact("Policy version", r.policyVersion)}</dl>`;
    html += list("Conditions", r.conditions) + list("Reasons for the outcome", r.adverseActionReasons);
    html += Array.isArray(r.agentReports) ? r.agentReports.map(report => `<article class="finding"><strong>${escapeHtml(report.agentName)} · ${escapeHtml(prettyStatus(report.status))}</strong><p>${escapeHtml(report.summary)}</p>${list("Findings", report.findings)}${list("Flags", report.redFlags)}</article>`).join("") : "";
  } else if (row.flow === "document") {
    html += `<dl class="profile-facts">${fact("Document type", r.analysis?.documentType ? prettyStatus(r.analysis.documentType) : null)}${fact("Next queue", r.workflowQueue)}${fact("Model-reported confidence (not a guarantee)", r.analysis?.confidencePct == null ? null : `${r.analysis.confidencePct}%`)}</dl>`;
    if (r.analysis?.keyFields) html += `<h3>Extracted fields</h3><dl class="profile-facts">${Object.entries(r.analysis.keyFields).map(([k,v]) => fact(prettyStatus(k), v)).join("")}</dl>`;
    html += list("Suggested actions", r.analysis?.actionItems) + list("Flags", r.flags);
  } else if (row.flow === "grievance") {
    html += `<dl class="profile-facts">${fact("Responsible team", r.route ? prettyStatus(r.route) : null)}${fact("Illustrative response deadline", r.slaHours == null ? null : `${r.slaHours} hours`)}</dl>`;
    html += list("Flags", r.flags);
    if (r.responseDraft) {
      html += `<h3>Draft acknowledgement (not sent)</h3><p>${escapeHtml(r.responseDraft.acknowledgement || "No acknowledgement supplied.")}</p>`;
      html += list("Proposed follow-up actions", r.responseDraft.proposedActions) + list("Referenced playbooks", r.responseDraft.citedPlaybooks);
      if (r.responseDraft.followUpPlan) html += `<p>${escapeHtml(r.responseDraft.followUpPlan)}</p>`;
    }
  } else if (row.flow === "incident") {
    html += "<p>Remediation in this demo is simulated; no real banking service is changed.</p>";
    html += list("Probable causes", r.diagnosis?.probableCauses) + list("Evidence", r.diagnosis?.evidence)
      + list("Referenced runbooks", r.diagnosis?.matchedRunbooks);
    if (Array.isArray(r.remediation?.steps)) html += list("Proposed actions",
      r.remediation.steps.map(step => `${step.action} · ${prettyStatus(step.riskLevel)} risk${step.requiresApproval ? " · human approval required" : ""}`));
    if (r.remediation?.rollbackPlan) html += `<h3>Rollback plan</h3><p>${escapeHtml(r.remediation.rollbackPlan)}</p>`;
    html += list("Execution log", r.executionLog) + list("Safety flags", r.guardrailFlags);
  }
  if (r.error) html += `<p class="inline-error">${escapeHtml(r.error)}</p>`;
  return html;
}

function nextStep(row) {
  const status = row.status;
  if (status === "PENDING_APPROVAL") return "A person must review this case. Open the review queue, inspect the packet, and record a decision.";
  if (status === "OPEN" || status === "RUNNING") return "The system is working. This page refreshes automatically; do not create another case.";
  if (status === "FAILED" || status === "EXECUTION_FAILED") return "Processing did not finish successfully. Read the error below. Check model configuration or the application console before starting a new attempt.";
  if (status === "BLOCKED") return "Safety or policy checks stopped processing. Read the outcome; do not keep resubmitting the same input.";
  if (status === "EXPIRED") return "The review deadline passed without a completed decision. Start a new reviewed attempt if needed.";
  if (status === "APPROVED" || status === "REJECTED") return "The human review is recorded. The original analysis below is retained for context; it is not a newly generated decision.";
  return "Read the business outcome below. Completed means processing finished; it does not necessarily mean a loan was approved.";
}

function approvalCard(item) {
  const meta = FLOW_META[item.flow] || { short: item.flow || "Workflow", icon: "•" };
  const details = Array.isArray(item.details) ? item.details.slice(0, 2).join(" · ") : "";
  const blind = item.blindFirstRequired && !item.blindFirstAssessment;
  return `<article class="approval-card">
    <div class="approval-card-head"><div><span class="flow-chip"><span class="flow-symbol">${escapeHtml(meta.icon)}</span>${escapeHtml(meta.short)}</span><h3>${escapeHtml(item.title || "Review requested")}</h3><small>${escapeHtml(shortId(item.id))} · opened ${escapeHtml(formatDate(item.createdAt))}</small></div><span class="status-pill pending">Pending review</span></div>
    <div class="approval-card-body">${details ? `<p>${escapeHtml(details)}</p>` : ""}${blind ? `<div class="blind-first-note">Independent blind-first assessment required. AI evidence is withheld until that step is complete.</div>` : `<p>Required role: ${escapeHtml(item.requiredRole || "assigned reviewer")}</p>`}</div>
    <div class="approval-card-foot"><small>Expires ${escapeHtml(formatDate(item.expiresAt, { month: "short", day: "numeric" }))}</small><button data-approval-id="${escapeHtml(item.id)}">Review packet&nbsp; →</button></div>
  </article>`;
}

function approvalsView() {
  const body = state.approvalError
    ? `<div class="inline-error">Could not load the review queue: ${escapeHtml(state.approvalError)}. Reviewer access is determined by the backend.</div>`
    : state.approvals.length
      ? `<div class="approval-grid">${state.approvals.map(approvalCard).join("")}</div>`
      : `<article class="panel"><div class="empty-state"><span class="empty-icon" aria-hidden="true">✓</span><strong>Your review queue is clear</strong><p>When a workflow requires an authorized human decision, its review packet will appear here.</p></div></article>`;
  return `${pageHeading("OPERATIONS / HUMAN REVIEW", "Review queue", "Review evidence and policy context before recording an authorized decision.")}${body}`;
}

function caseDetailView(row) {
  const result = row.result;
  const latest = formatDate(row.updatedAt || row.createdAt);
  const terminal = !["OPEN", "QUEUED", "ACCEPTED", "RUNNING", "IN_PROGRESS", "PENDING", "PENDING_APPROVAL"].includes(String(row.status).toUpperCase());
  return `<button class="detail-back" data-view="cases">← &nbsp; Back to cases</button>
    <div class="detail-header"><div><div class="eyebrow">${escapeHtml((FLOW_META[row.flow] || {}).label || row.flow || "WORKFLOW")}</div><h1>Case workspace</h1><div class="detail-id">${escapeHtml(row.caseId)}</div></div><div><span class="status-pill ${statusClass(row.status)}">${escapeHtml(prettyStatus(row.status))}</span><button class="icon-button" id="refresh-case" aria-label="Refresh case status" title="Refresh case status" style="margin-left:8px">↻</button></div></div>
    <div class="detail-grid">
      <section class="panel detail-section"><h2>What happens next?</h2><p role="status">${escapeHtml(nextStep(row))}</p>${row.status === "PENDING_APPROVAL" ? '<button class="button button-primary" data-view="approvals">Go to review queue →</button>' : ""}<h2>Outcome and available evidence</h2>${readableResult(row)}${result ? `<details><summary>Technical details (optional)</summary><pre class="result-json">${escapeHtml(JSON.stringify(result, null, 2))}</pre></details>` : ""}<p>Displayed content is returned by the backend. Model-generated summaries are not independently verified facts.</p></section>
      <aside>
        <section class="panel detail-section"><h2>Case details</h2><div class="detail-meta"><div class="meta-item"><small>Workflow</small><strong>${escapeHtml((FLOW_META[row.flow] || {}).label || row.flow)}</strong></div><div class="meta-item"><small>Version</small><strong>${escapeHtml(row.version)}</strong></div><div class="meta-item"><small>Created</small><strong>${escapeHtml(formatDate(row.createdAt))}</strong></div><div class="meta-item"><small>Last updated</small><strong>${escapeHtml(latest)}</strong></div></div></section>
        <section class="panel detail-section" style="margin-top:12px"><h2>Progress</h2><ul class="timeline"><li><span class="timeline-dot"></span><span>Received by the case service<br><small>${escapeHtml(formatDate(row.createdAt))}</small></span></li><li><span class="timeline-dot" style="background:${terminal ? "#58a995" : "#d49a34"}"></span><span>${terminal ? "Latest state recorded" : "Processing status"}<br><small>${escapeHtml(prettyStatus(row.status))} · ${escapeHtml(formatDate(row.updatedAt))}</small></span></li></ul>${terminal ? "" : `<p>Progress is refreshed from the server. No percentage or agent activity is simulated.</p>`}</section>
      </aside>
    </div>`;
}

function approvalDetailView(item) {
  const packet = item.evidencePacket || {};
  const blind = item.blindFirstRequired && !item.blindFirstAssessment;
  const lists = [
    ["Case facts", packet.minimizedCaseFacts],
    ["Policy context", packet.policyEnvelope],
    ["Invariant checks", packet.invariantResults],
    ["Uncertainty signals", packet.uncertaintySignals]
  ];
  const findings = Array.isArray(packet.agentFindings) ? packet.agentFindings : [];
  const open = item.status === "PENDING";
  return `<button class="detail-back" data-view="approvals">← &nbsp; Back to review queue</button>
    <div class="detail-header"><div><div class="eyebrow">${escapeHtml((FLOW_META[item.flow] || {}).label || item.flow || "WORKFLOW")} / REVIEW PACKET</div><h1>${escapeHtml(item.title || "Review request")}</h1><div class="detail-id">${escapeHtml(item.id)} · version ${escapeHtml(item.version)}</div></div><span class="status-pill ${statusClass(item.status)}">${escapeHtml(prettyStatus(item.status))}</span></div>
    <div class="detail-grid">
      <section class="panel detail-section"><h2>Evidence packet</h2>${blind ? `<div class="blind-first-note">Blind-first review is required. AI evidence is correctly withheld. Complete the independent assessment through the designated reviewer process before deciding.</div>` : ""}
      ${lists.map(([label, values]) => Array.isArray(values) && values.length ? `<h3>${escapeHtml(label)}</h3><ul>${values.map(value => `<li>${escapeHtml(value)}</li>`).join("")}</ul>` : "").join("")}
      ${findings.length && !blind ? `<h3>Agent findings</h3>${findings.map(finding => `<div class="trust-note"><strong>${escapeHtml(finding.agentId || "Agent")}</strong> · ${escapeHtml(finding.status || "Finding")} · risk ${escapeHtml(finding.riskScore ?? "—")}<br>${escapeHtml(finding.summary || "")}${Array.isArray(finding.citations) && finding.citations.length ? `<br><small>Sources: ${escapeHtml(finding.citations.join(", "))}</small>` : ""}</div>`).join("")}` : ""}
      ${!blind && packet.aiRecommendation ? `<h3>AI recommendation</h3><p>${escapeHtml(packet.aiRecommendation)}</p>` : ""}
      ${!blind && packet.sideEffects ? `<h3>What your decision does</h3><p><strong>If approved:</strong> ${escapeHtml((packet.sideEffects.onApprove || []).join(" · ") || "No action preview supplied.")}</p><p><strong>If rejected:</strong> ${escapeHtml((packet.sideEffects.onReject || []).join(" · ") || "No action preview supplied.")}</p>` : ""}
      ${!lists.some(([, values]) => Array.isArray(values) && values.length) && !findings.length && !blind ? `<p>No additional evidence fields were returned in this packet.</p>` : ""}
      </section>
      <aside><section class="panel detail-section"><h2>Review context</h2><div class="detail-meta"><div class="meta-item"><small>Required role</small><strong>${escapeHtml(item.requiredRole || "Assigned reviewer")}</strong></div><div class="meta-item"><small>Expires</small><strong>${escapeHtml(formatDate(item.expiresAt))}</strong></div><div class="meta-item"><small>Risk score</small><strong>${escapeHtml(packet.maxRiskScore ?? "Not provided")}</strong></div><div class="meta-item"><small>Recommendation</small><strong>${escapeHtml(blind ? "Withheld (blind-first)" : packet.aiRecommendation || "Not provided")}</strong></div></div>${packet.criticResult && !blind ? `<p>${escapeHtml(packet.criticResult)}</p>` : ""}</section>
      ${!open ? '<section class="panel detail-section"><h2>Review already closed</h2><p>This packet is no longer pending. Return to the queue or reopen the associated case for the latest state.</p></section>' : blind ? `<section class="panel detail-section" style="margin-top:12px"><h2>Independent assessment first</h2><p>Record your own assessment before seeing AI recommendations. This does not execute the action. Then review the revealed packet and make the final decision.</p><button class="button button-primary" id="open-decision">Record independent assessment →</button></section>` : `<section class="panel detail-section" style="margin-top:12px"><h2>Your responsibility</h2><p>Confirm the current evidence and applicable policy before deciding. Decisions are version-checked and authorization is enforced by the server.</p><button class="button button-primary" id="open-decision" style="width:100%">Record a decision&nbsp; →</button></section>`}
      </aside>
    </div>`;
}

function render() {
  $("#case-nav-count").textContent = state.caseError ? "!" : String(state.cases.length);
  $("#approval-nav-count").textContent = state.approvalError ? "!" : String(state.approvals.length);
  document.querySelectorAll(".nav-link").forEach(link => {
    const active = link.dataset.view === (state.view === "case-detail" ? "cases" : state.view === "approval-detail" ? "approvals" : state.view);
    link.classList.toggle("is-active", active);
    if (active) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current");
  });
  const labels = { overview: "Overview", cases: "Cases", approvals: "Review queue", "case-detail": "Case details", "approval-detail": "Review packet" };
  $("#page-crumb").textContent = labels[state.view] || "Overview";
  if (state.view === "overview") content.innerHTML = overviewView();
  else if (state.view === "cases") content.innerHTML = casesView();
  else if (state.view === "approvals") content.innerHTML = approvalsView();
  else if (state.view === "case-detail") {
    const row = state.cases.find(item => item.caseId === state.selectedCaseId);
    content.innerHTML = row ? caseDetailView(row) : `<button class="detail-back" data-view="cases">← &nbsp; Back to cases</button><div class="panel inline-error">This case is not in the current page of results. Refresh the case list or verify your access.</div>`;
  } else if (state.view === "approval-detail") {
    const item = state.selectedApproval;
    content.innerHTML = item ? approvalDetailView(item) : approvalsView();
  }
  if (state.view !== "case-detail") window.clearInterval(state.pollTimer);
  else startCasePolling();
}

function go(view) {
  state.view = view;
  render();
  if (["overview", "cases", "approvals"].includes(view)) window.location.hash = view;
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function rememberCase(id) {
  window.location.hash = `case/${encodeURIComponent(id)}`;
}

async function restoreRoute() {
  const route = window.location.hash.slice(1);
  if (route.startsWith("case/")) {
    state.selectedCaseId = decodeURIComponent(route.slice(5));
    state.view = "case-detail";
    await refreshSelectedCase();
  } else if (["overview", "cases", "approvals"].includes(route)) {
    state.view = route;
    render();
  }
}

function showNewCase(flow = "loan") {
  loanWizard.profile = null;
  loanWizard.step = "find";
  loanWizard.details = null;
  pendingSubmission = null;
  $("#flow-select").value = Object.hasOwn(FLOW_FIELDS, flow) ? flow : "loan";
  updateFlowFields();
  $("#form-error").hidden = true;
  caseDialog.showModal();
}

function updateFlowFields() {
  const flow = $("#flow-select").value;
  $("#case-dialog-title").textContent = `${FLOW_META[flow].label} case`;
  $("#case-dialog-copy").textContent = {
    loan: "Find an applicant, review their profile, then add the loan details.",
    incident: "Describe the operational incident so the response workflow can assess it.",
    grievance: "Record a minimum-necessary report and route it for appropriate review.",
    document: "Submit extracted text for structured document analysis."
  }[flow];
  $("#submit-case").hidden = false;
  $("#load-demo-sample").hidden = flow === "loan";
  $("#submit-case").querySelector("span").textContent = "Submit case";
  if (flow === "loan") {
    renderLoanFlow();
    if (loanWizard.step === "find") searchApplicants("");
  } else $("#flow-fields").innerHTML = FLOW_FIELDS[flow];
}

async function loadDemoSample(button) {
  const flow = $("#flow-select").value;
  if (flow === "loan") return;
  button.disabled = true;
  try {
    const sample = await api(`/api/${{incident:"incidents", grievance:"grievances", document:"documents"}[flow]}/sample`);
    if ($("#flow-select").value !== flow) return;
    const idField = {incident:"incidentId", grievance:"grievanceId", document:"documentId"}[flow];
    sample[idField] = `DEMO-${crypto.randomUUID()}`;
    for (const [name, value] of Object.entries(sample)) {
      const field = $("#case-form").elements.namedItem(name);
      if (!field) continue;
      if (field.type === "checkbox") field.checked = Boolean(value);
      else field.value = Array.isArray(value) ? value.join(", ") : value ?? "";
    }
    announce("Demo example loaded. Review it, then submit when ready.");
  } catch (error) {
    $("#form-error").textContent = `Could not load the demo example: ${error.message}`;
    $("#form-error").hidden = false;
  } finally { button.disabled = false; }
}

function profileSummary(p) {
  const money = value => new Intl.NumberFormat(undefined, { style: "currency", currency: "USD", maximumFractionDigits: 0 }).format(value);
  return `<div class="applicant-profile"><div class="eyebrow">SYNTHETIC CUSTOMER RECORD</div><h3>${escapeHtml(p.fullName)}</h3>
    <p>${escapeHtml(p.applicantId)}</p><dl class="profile-facts">
    <div><dt>Annual income</dt><dd>${escapeHtml(money(p.annualIncome))}</dd></div>
    <div><dt>Employment</dt><dd>${escapeHtml(p.employer)} · ${escapeHtml(p.employmentYears)} years</dd></div>
    <div><dt>Monthly debt</dt><dd>${escapeHtml(money(p.existingMonthlyDebt))}</dd></div>
    <div><dt>Document references</dt><dd>${escapeHtml(p.documents.length ? p.documents.join(", ") : "None supplied during onboarding")}</dd></div>
    <div><dt>Source / status</dt><dd>${escapeHtml(p.source)} · ${escapeHtml(prettyStatus(p.verificationStatus))}</dd></div>
    <div><dt>Recorded</dt><dd>${escapeHtml(formatDate(p.recordedAt))} · version ${escapeHtml(p.version)}</dd></div></dl>
    <p>Demo records and document references are simulated, not verified customer evidence. New applicants use the existing generic simulated bureau scenario.</p></div>`;
}

function renderLoanFlow() {
  const container = $("#flow-fields");
  const step = loanWizard.step;
  const stages = ["Find applicant", "Review profile", "Loan details", "Confirm"];
  const current = { find: 0, onboard: 0, profile: 1, details: 2, confirm: 3 }[step];
  const steps = `<ol class="wizard-steps" aria-label="Loan application steps">${stages.map((label, index) => `<li ${current === index ? 'aria-current="step"' : ""}>${index + 1}. ${label}</li>`).join("")}</ol>`;
  let body = "";
  $("#submit-case").hidden = step !== "details" && step !== "confirm";
  $("#submit-case").querySelector("span").textContent = step === "details" ? "Review application" : "Confirm and submit";
  if (step === "find") {
    body = `<label class="field-label" for="applicant-search">Find by name or customer reference</label>
      <div class="applicant-search"><input class="input-control" id="applicant-search" placeholder="Try Jane, Alex, Sam, or A001" autocomplete="off"><button type="button" class="button button-secondary" id="search-applicants">Search</button></div>
      <div id="applicant-results" role="status">Loading demo applicants…</div>
      <button type="button" class="text-button" id="onboard-applicant">New applicant? Create a demo customer →</button>
      <p class="field-help">Customer records are tenant-scoped and held in memory for this demo. Newly onboarded profiles reset when the application restarts.</p>`;
  } else if (step === "onboard") {
    body = `<h3>Onboard a demo applicant</h3><p class="field-help">Create a reusable synthetic record. Income and employment are declared; model/tool checks run during evaluation.</p>
      <label class="field-label" for="new-name">Full name *</label><input class="input-control" id="new-name" name="fullName" required maxlength="120" autocomplete="off">
      <label class="field-label" for="new-suffix">Synthetic SSN last four *</label><input class="input-control" id="new-suffix" name="ssnLast4" required pattern="[0-9]{4}" maxlength="4" inputmode="numeric" autocomplete="off">
      <label class="field-label" for="new-employer">Employer *</label><input class="input-control" id="new-employer" name="employer" required maxlength="120">
      <div class="field-row"><div><label class="field-label" for="new-income">Annual income (USD) *</label><input class="input-control" id="new-income" name="annualIncome" type="number" required min="0" step="0.01"></div>
      <div><label class="field-label" for="new-years">Years employed *</label><input class="input-control" id="new-years" name="employmentYears" type="number" required min="0" max="60" step="1"></div></div>
      <label class="field-label" for="new-debt">Existing monthly debt (USD) *</label><input class="input-control" id="new-debt" name="existingMonthlyDebt" type="number" required min="0" step="0.01">
      <div class="wizard-actions"><button type="button" class="text-button" id="find-applicant">← Find an existing applicant</button><button type="button" class="button button-primary" id="save-applicant">Create applicant</button></div>`;
  } else if (step === "profile") {
    body = `${profileSummary(loanWizard.profile)}<div class="wizard-actions"><button type="button" class="text-button" id="find-applicant">← Change applicant</button><button type="button" class="button button-primary" id="loan-details">Continue to loan details →</button></div>`;
  } else if (step === "details") {
    body = `<div class="selected-applicant"><strong>${escapeHtml(loanWizard.profile.fullName)}</strong><span>${escapeHtml(loanWizard.profile.applicantId)}</span></div>
      <label class="field-label" for="requestedAmount">Requested amount (USD) *</label><input class="input-control" id="requestedAmount" name="requestedAmount" type="number" min="1000" step="0.01" required value="${escapeHtml(loanWizard.details?.requestedAmount ?? "")}">
      <label class="field-label" for="loanPurpose">Loan purpose *</label><input class="input-control" id="loanPurpose" name="loanPurpose" required maxlength="200" value="${escapeHtml(loanWizard.details?.loanPurpose ?? "")}">
      <label class="field-label" for="termMonths">Term in months *</label><input class="input-control" id="termMonths" name="termMonths" type="number" required min="6" max="360" step="1" value="${escapeHtml(loanWizard.details?.termMonths ?? 60)}">
      <button type="button" class="text-button" id="review-profile">← Review applicant profile</button>`;
  } else {
    body = `${profileSummary(loanWizard.profile)}<h3>Loan application</h3><dl class="profile-facts"><div><dt>Amount</dt><dd>${escapeHtml(new Intl.NumberFormat(undefined, {style:"currency", currency:"USD"}).format(loanWizard.details.requestedAmount))}</dd></div><div><dt>Purpose</dt><dd>${escapeHtml(loanWizard.details.loanPurpose)}</dd></div><div><dt>Term</dt><dd>${escapeHtml(loanWizard.details.termMonths)} months</dd></div></dl>
      <label class="confirmation-label"><input type="checkbox" id="loan-confirmed" required> I reviewed this demo applicant's profile and the loan details.</label>
      <button type="button" class="text-button" id="loan-details">← Edit loan details</button>`;
  }
  container.innerHTML = steps + body;
  $("#form-error").hidden = true;
}

let applicantSearchSequence = 0;
async function searchApplicants(query) {
  const sequence = ++applicantSearchSequence;
  const results = $("#applicant-results");
  if (!results) return;
  results.textContent = "Searching demo customers…";
  try {
    const profiles = await api(`/api/loans/applicants?query=${encodeURIComponent(query)}`);
    if (sequence !== applicantSearchSequence || !results.isConnected) return;
    if (!Array.isArray(profiles)) throw new Error("Invalid applicant directory response");
    results.innerHTML = profiles.length ? profiles.map(p => `<button type="button" class="applicant-option" data-applicant="${escapeHtml(p.applicantId)}"><span><strong>${escapeHtml(p.fullName)}</strong><small>${escapeHtml(p.applicantId)} · ${escapeHtml(p.employer)}</small></span><span aria-hidden="true">→</span></button>`).join("") : "<p>No matching applicants. Search again or onboard a new demo applicant.</p>";
  } catch (error) {
    if (sequence === applicantSearchSequence && results.isConnected) results.textContent = `Applicant lookup failed: ${error.message}`;
  }
}

async function selectApplicant(id) {
  try {
    const profile = await api(`/api/loans/applicants/${encodeURIComponent(id)}`);
    if ($("#flow-select").value !== "loan") return;
    loanWizard.profile = profile;
    loanWizard.step = "profile";
    loanWizard.details = null;
    pendingSubmission = null;
    renderLoanFlow();
  } catch (error) {
    $("#form-error").textContent = `Could not load applicant: ${error.message}`;
    $("#form-error").hidden = false;
  }
}

async function onboardApplicant(button) {
  const form = $("#case-form");
  if (!form.reportValidity()) return;
  const data = new FormData(form);
  const payload = {
    fullName: String(data.get("fullName")).trim(), ssnLast4: String(data.get("ssnLast4")),
    employer: String(data.get("employer")).trim(), annualIncome: Number(data.get("annualIncome")),
    employmentYears: Number(data.get("employmentYears")), existingMonthlyDebt: Number(data.get("existingMonthlyDebt"))
  };
  button.disabled = true;
  try {
    loanWizard.profile = await api("/api/loans/applicants", { method: "POST", body: JSON.stringify(payload) });
    loanWizard.step = "profile";
    renderLoanFlow();
    announce("Demo applicant created. Review the stored profile before continuing.");
  } catch (error) {
    $("#form-error").textContent = error.message;
    $("#form-error").hidden = false;
  } finally { button.disabled = false; }
}

function formPayload(form, flow) {
  const data = new FormData(form);
  const get = name => String(data.get(name) || "").trim();
  if (flow === "incident") return {
    incidentId: get("incidentId"), title: get("title"), description: get("description"),
    service: get("service"), environment: get("environment"), reporter: get("reporter"),
    tags: get("tags") ? get("tags").split(",").map(x => x.trim()).filter(Boolean) : []
  };
  if (flow === "grievance") return {
    grievanceId: get("grievanceId"), projectId: get("projectId"), description: get("description"),
    channel: get("channel"), location: get("location"), anonymous: data.get("anonymous") === "true"
  };
  return { documentId: get("documentId"), title: get("title"), content: get("content") };
}

async function submitCase(event) {
  event.preventDefault();
  if (submissionInFlight) return;
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const flow = $("#flow-select").value;
  if (flow === "loan" && loanWizard.step === "find") {
    searchApplicants($("#applicant-search").value.trim());
    return;
  }
  if (flow === "loan" && loanWizard.step === "onboard") {
    onboardApplicant($("#save-applicant"));
    return;
  }
  if (flow === "loan" && loanWizard.step === "details") {
    const data = new FormData(form);
    loanWizard.details = { requestedAmount: Number(data.get("requestedAmount")),
      loanPurpose: String(data.get("loanPurpose")).trim(), termMonths: Number(data.get("termMonths")) };
    loanWizard.step = "confirm";
    renderLoanFlow();
    return;
  }
  if (flow === "loan" && loanWizard.step !== "confirm") return;
  const payload = flow === "loan" ? { ...loanWizard.details, profileVersion: loanWizard.profile.version,
    confirmed: $("#loan-confirmed").checked } : formPayload(form, flow);
  const error = $("#form-error");
  const button = $("#submit-case");
  submissionInFlight = true;
  $("#flow-select").disabled = true;
  button.querySelector("span").textContent = "Submitting…";
  error.hidden = true;
  const path = flow === "loan" ? `/api/loans/applicants/${encodeURIComponent(loanWizard.profile.applicantId)}/cases`
    : `/api/v1/${encodeURIComponent(flow)}/cases`;
  const signature = path + JSON.stringify(payload);
  if (!pendingSubmission || pendingSubmission.signature !== signature) {
    pendingSubmission = { signature, key: crypto.randomUUID() };
  }
  try {
    button.disabled = true;
    const response = await api(path, {
      method: "POST",
      headers: { "Idempotency-Key": pendingSubmission.key },
      body: JSON.stringify(payload)
    });
    caseDialog.close();
    form.reset();
    await loadData();
    if (!state.cases.some(row => row.caseId === response.caseId)) state.cases.unshift(response);
    state.selectedCaseId = response.caseId;
    state.view = "case-detail";
    rememberCase(response.caseId);
    render();
    announce("Case accepted by the workflow service.");
  } catch (problem) {
    error.textContent = problem.status === 401 || problem.status === 403
      ? `The backend did not authorize this request: ${problem.message}`
      : problem.status === 422
        ? "The request was blocked by backend safety checks. Review the information and follow the approved process."
        : problem.message;
    error.hidden = false;
  } finally {
    submissionInFlight = false;
    $("#flow-select").disabled = false;
    button.disabled = false;
    button.querySelector("span").textContent = flow === "loan" ? "Confirm and submit" : "Submit case";
  }
}

async function refreshSelectedCase() {
  if (!state.selectedCaseId || caseRefreshInFlight) return;
  const id = state.selectedCaseId;
  caseRefreshInFlight = true;
  try {
    const row = await api(`/api/v1/cases/${encodeURIComponent(id)}`);
    if (state.view !== "case-detail" || state.selectedCaseId !== id) return;
    const index = state.cases.findIndex(item => item.caseId === row.caseId);
    const changed = index < 0 || JSON.stringify(state.cases[index]) !== JSON.stringify(row);
    if (index >= 0) state.cases[index] = row;
    else state.cases.unshift(row);
    showGlobalAlert("");
    if (changed || !$("#refresh-case")) render();
    else startCasePolling();
  } catch (error) {
    window.clearInterval(state.pollTimer);
    showGlobalAlert(`Case refresh failed: ${error.message}. Use Refresh to retry; do not resubmit the case.`);
    if (state.view === "case-detail" && state.selectedCaseId === id
        && !state.cases.some(item => item.caseId === id)) {
      content.innerHTML = '<button class="detail-back" data-view="cases">← Back to cases</button><p>Could not load this case. Check the message above and use Refresh to retry.</p>';
    }
  } finally {
    caseRefreshInFlight = false;
  }
}

function startCasePolling() {
  window.clearInterval(state.pollTimer);
  const row = state.cases.find(item => item.caseId === state.selectedCaseId);
  if (document.hidden || !row || !["OPEN", "QUEUED", "ACCEPTED", "RUNNING", "IN_PROGRESS", "PENDING", "PENDING_APPROVAL"].includes(String(row.status).toUpperCase())) return;
  state.pollTimer = window.setInterval(refreshSelectedCase, 3500);
}

async function openApproval(id) {
  try {
    const item = await api(`/api/approvals/${encodeURIComponent(id)}`);
    state.selectedApproval = item;
    state.view = "approval-detail";
    render();
    return item;
  } catch (error) {
    announce(`Could not open review packet: ${error.message}`);
    return null;
  }
}

async function submitDecision(decision) {
  if (decisionInFlight) return;
  const item = state.selectedApproval;
  if (!item) return;
  const form = $("#decision-form");
  if (!form.reportValidity()) return;
  const error = $("#decision-error");
  const reasonCode = $("#reason-code").value.trim();
  const comment = $("#decision-comment").value.trim();
  if (!reasonCode) {
    error.textContent = "Enter a reason code, for example DEMO_REVIEWED.";
    error.hidden = false;
    return;
  }
  decisionInFlight = true;
  const blind = item.blindFirstRequired && !item.blindFirstAssessment;
  document.querySelectorAll("[data-decision]").forEach(button => { button.disabled = true; });
  try {
    if (blind) {
      await api(`/api/approvals/${encodeURIComponent(item.id)}/blind-first`, {
        method: "POST", body: JSON.stringify({ decision, reasonCode, note: comment })
      });
      decisionDialog.close();
      const refreshed = await openApproval(item.id);
      if (refreshed) announce("Independent assessment saved. Review the revealed AI evidence, then record a final decision.");
      return;
    }
    const result = await api(`/api/v1/approvals/${encodeURIComponent(item.id)}/decisions`, {
      method: "POST",
      headers: { "If-Match": `"${item.version}"` },
      body: JSON.stringify({ decision, reasonCode, comment })
    });
    decisionDialog.close();
    state.selectedApproval = null;
    state.view = "approvals";
    window.location.hash = "approvals";
    await loadData();
    announce(result?.status === "EXECUTION_FAILED"
      ? "Approval was recorded, but the action failed. Reopen the case for its execution outcome."
      : `Decision recorded: ${prettyStatus(result?.status || decision)}.`);
  } catch (problem) {
    error.textContent = problem.status === 412 || problem.status === 409
      ? "This approval changed since it was opened. Refresh the queue and review the current evidence before deciding."
      : problem.status === 401 || problem.status === 403
        ? `The backend did not authorize this decision: ${problem.message}`
        : problem.message;
    error.hidden = false;
    if (problem.status === 409 || problem.status === 412) {
      decisionDialog.close();
      const refreshed = await openApproval(item.id);
      if (refreshed) announce("The approval changed. Review the refreshed packet before deciding.");
    }
  } finally {
    decisionInFlight = false;
    document.querySelectorAll("[data-decision]").forEach(button => { button.disabled = false; });
  }
}

document.addEventListener("click", event => {
  const target = event.target.closest("button");
  if (!target) return;
  if (decisionInFlight && target.closest("#decision-dialog")) return;
  if (submissionInFlight && target.closest("#case-dialog")) return;
  if (target.dataset.startFlow) { showNewCase(target.dataset.startFlow); return; }
  if (target.id === "load-demo-sample") { loadDemoSample(target); return; }
  if (target.id === "load-more-cases") { loadMoreCases(target); return; }
  if (target.dataset.applicant) { selectApplicant(target.dataset.applicant); return; }
  if (target.id === "search-applicants") { searchApplicants($("#applicant-search").value.trim()); return; }
  if (target.id === "onboard-applicant") { loanWizard.step = "onboard"; renderLoanFlow(); return; }
  if (target.id === "find-applicant") {
    loanWizard.profile = null; loanWizard.step = "find"; loanWizard.details = null;
    renderLoanFlow(); searchApplicants(""); return;
  }
  if (target.id === "save-applicant") { onboardApplicant(target); return; }
  if (target.id === "loan-details") { loanWizard.step = "details"; renderLoanFlow(); return; }
  if (target.id === "review-profile") { loanWizard.step = "profile"; renderLoanFlow(); return; }
  if (target.dataset.view) { go(target.dataset.view); return; }
  if (target.hasAttribute("data-new-case")) { showNewCase(); return; }
  if (target.hasAttribute("data-close-dialog")) { caseDialog.close(); decisionDialog.close(); return; }
  if (target.dataset.caseId) {
    state.selectedCaseId = target.dataset.caseId;
    state.view = "case-detail";
    rememberCase(state.selectedCaseId);
    render();
    refreshSelectedCase();
    return;
  }
  if (target.dataset.approvalId) { openApproval(target.dataset.approvalId); return; }
  if (target.id === "refresh-button") { loadData(); return; }
  if (target.id === "refresh-case") { refreshSelectedCase(); return; }
  if (target.id === "open-decision") {
    $("#decision-error").hidden = true;
    $("#decision-form").reset();
    const blind = state.selectedApproval?.blindFirstRequired && !state.selectedApproval?.blindFirstAssessment;
    $("#decision-dialog h2").textContent = blind ? "Independent assessment" : "Record your decision";
    $("#decision-dialog .modal-intro").textContent = blind
      ? "Record your own view without AI recommendations. No action is executed until a separate final decision."
      : "Review the evidence and side effects. Your decision is submitted against the current approval version.";
    decisionDialog.showModal();
    return;
  }
  if (target.dataset.decision) { submitDecision(target.dataset.decision); return; }
});

document.addEventListener("change", event => {
  if (event.target.id === "flow-select") updateFlowFields();
  if (event.target.id === "flow-filter") { state.filterFlow = event.target.value; render(); }
  if (event.target.id === "status-filter") { state.filterStatus = event.target.value; render(); }
});

document.addEventListener("input", event => {
  if (event.target.id === "case-search") {
    const cursor = event.target.selectionStart;
    state.search = event.target.value;
    render();
    const replacement = $("#case-search");
    replacement?.focus();
    replacement?.setSelectionRange(cursor, cursor);
  }
});

function initializeWorkspace() {
  $("#case-form").addEventListener("submit", submitCase);
  $("#case-dialog").addEventListener("cancel", event => { if (submissionInFlight) event.preventDefault(); });
  $("#decision-form").addEventListener("submit", event => event.preventDefault());
  $("#decision-dialog").addEventListener("cancel", event => { if (decisionInFlight) event.preventDefault(); });
  $("#dismiss-banner").addEventListener("click", () => { $(".dev-banner").hidden = true; });
  $("#flow-select").value = "loan";
  loadData().then(restoreRoute).catch(error => showGlobalAlert(`Workspace startup failed: ${error.message}`));
}

document.addEventListener("DOMContentLoaded", initializeWorkspace);
window.addEventListener("hashchange", () => {
  restoreRoute().catch(error => showGlobalAlert(`Could not open the requested page: ${error.message}`));
});
document.addEventListener("visibilitychange", () => {
  if (document.hidden) window.clearInterval(state.pollTimer);
  else if (state.view === "case-detail") refreshSelectedCase();
});
