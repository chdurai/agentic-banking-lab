const elements = new Map();
function element() {
  return {
    textContent: "", disabled: false, hidden: true, value: "missing", style: {}, children: [],
    listeners: {}, addEventListener(event, handler) { this.listeners[event] = handler; },
    setAttribute(name, value) { this[name] = value; },
    replaceChildren(...items) { this.children = items; }, append(...items) { this.children.push(...items); }
  };
}
globalThis.document = {
  querySelector(selector) {
    if (!elements.has(selector)) elements.set(selector, element());
    return elements.get(selector);
  },
  createElement() { return element(); }
};
globalThis.window = globalThis;
globalThis.location = {search: "?showcase=1"};
globalThis.history = {replaceState(a, b, value) { location.search = value; }};
globalThis.URLSearchParams = class {
  constructor(search) { this.search = search; }
  get(key) {
    return (this.search.replace(/^\?/, "").split("&").map(pair => pair.split("=")).find(pair => pair[0] === key) || [])[1] || null;
  }
};
globalThis.crypto = {randomUUID: () => "synthetic-test-key"};
let time = 0;
Date.now = () => time;
globalThis.setTimeout = fn => { time += 400; fn(); };
let scenario = "success";
let releaseReplay;
const requests = [];
globalThis.fetch = async (url, options = {}) => {
  requests.push({url, options});
  let status = 200;
  let body;
  if (url === "/api/showcase") {
    if (scenario === "unavailable") status = 503;
    body = {mode: scenario === "wrong-mode" ? "LIVE" : "SCRIPTED", data: "SYNTHETIC_ONLY"};
  } else if (url === "/api/showcase/missing") body = {caseId: "scripted-case"};
  else if (url === "/api/v1/cases/scripted-case") {
    body = {status: scenario === "wait" ? "RUNNING" : "PENDING_APPROVAL", result: {decision: "MANUAL_REVIEW"}};
  } else if (url === "/api/v1/cases/scripted-case/investigation") {
    const uncertain = scenario === "uncertain";
    body = {run: {state: uncertain ? "LIMIT_REACHED" : scenario === "wait" ? "RUNNING" : "COMPLETE", gap: "MISSING_INCOME"},
      attempts: [
        {ordinal_no: 1, kind: "MODEL", state: "SUCCEEDED", attempt_id: "model-1", tool_name: "proposal",
          payload: JSON.stringify({providerCalls: 1, promptTokens: 100, completionTokens: 50, estimatedCostUsd: 0})},
        {ordinal_no: 2, kind: "TOOL", state: uncertain ? "UNCERTAIN" : "SUCCEEDED", tool_name: "loan.documents.v1", attempt_id: "tool-1",
          payload: uncertain ? null : '{"source":"SYNTHETIC_ONLY"}'}
      ]};
  } else if (url.includes("/comparison")) body = {
    mode: "FROZEN_POLICY_COMPARISON_NOT_MODEL_REEXECUTION", mutationCalls: 0,
    baseline: {envelope: {floor: "APPROVED", apr: 5.5, internalReasonCodes: []}},
    investigated: {envelope: {floor: "MANUAL_REVIEW", apr: 6, internalReasonCodes: ["UNAVAILABLE_LOAN_INVESTIGATOR"]}},
    findings: [{agent: "loan-investigator", coverage: "RECORDED_SYNTHETIC_SOURCE"}]
  };
  else if (url.endsWith("/passport")) body = {integrity: "CONTENT_CONSISTENCY_ONLY", sha256: "synthetic-digest"};
  else if (url.endsWith("/replay")) {
    if (scenario === "delayed-replay") await new Promise(resolve => { releaseReplay = resolve; });
    status = scenario === "tampered" ? 500 : 200;
    body = {providerCalls: 0, toolCalls: 0, mutationCalls: 0};
  } else if (url.endsWith("/cancel")) body = {status: "CANCELLATION_REQUESTED"};
  else if (url.endsWith("/review-outcome")) {
    status = scenario === "receipt-unavailable" ? 409 : 200;
    body = {mode: "READ_ONLY_CURRENT_REVIEW_OUTCOME", caseId: "scripted-case",
      passportSha256: "frozen-passport", observedAt: "2026-01-01T00:00:00Z",
      reviews: [{approvalId: "review-1", reviewStatus: "APPROVED",
        effectState: scenario === "receipt" ? "SIMULATED_RECEIPT_CONFIRMED" : "APPROVED_EFFECT_NOT_CONFIRMED",
        receipt: scenario === "receipt" ? {receiptId: "sim-test", approvalEvidenceSha256: "sealed-evidence"} : null}]};
  }
  else throw new Error("Unexpected UI request: " + url);
  return {ok: status === 200, status, json: async () => body};
};
const testResults = [];
function assert(condition, description) {
  if (!condition) throw new Error(description);
  testResults.push(description);
}
async function runInvestigationTests() {
  await window.launchSyntheticInvestigation();
  assert(document.querySelector("#investigate-status").textContent.includes("recommendation: MANUAL_REVIEW"), "Reviewer recommendation is not an executed effect");
  assert(document.querySelector("#investigate-timeline").children.length === 2, "Persisted model and tool attempts are visible");
  assert(document.querySelector("#investigate-budget").textContent.includes("150 synthetic tokens"), "Synthetic usage is visibly labelled");
  assert(!document.querySelector("#investigate-freeze").disabled && document.querySelector("#investigate-replay").disabled, "Playback requires a frozen passport");
  await document.querySelector("#investigate-freeze").listeners.click();
  assert(!document.querySelector("#investigate-replay").disabled, "Verified freeze enables playback");
  assert(!document.querySelector("#investigate-compare").disabled, "Verified freeze enables isolated comparison");
  await document.querySelector("#investigate-outcome").listeners.click();
  assert(document.querySelector("#investigate-review-outcome").children[1].textContent.includes("APPROVED_EFFECT_NOT_CONFIRMED"),
    "Approval without a receipt is not presented as a completed effect");
  scenario = "receipt";
  await document.querySelector("#investigate-outcome").listeners.click();
  assert(document.querySelector("#investigate-review-outcome").children[1].textContent.includes("sim-test"),
    "Verified synthetic receipt identity is visible");
  scenario = "receipt-unavailable";
  await document.querySelector("#investigate-outcome").listeners.click();
  assert(document.querySelector("#investigate-review-outcome").hidden &&
    document.querySelector("#investigate-status").textContent.includes("not confirmed"),
    "Failed refresh hides stale confirmed receipt and surfaces refusal");
  scenario = "success";
  document.querySelector("#investigate-term").value = "120";
  await document.querySelector("#investigate-compare").listeners.click();
  assert(requests.some(request => request.url.endsWith("/comparison?termMonths=120")), "Policy-only term scenario uses read-only comparison route");
  assert(document.querySelector("#investigate-playback").textContent.includes("NOT_MODEL_REEXECUTION"), "Policy comparison is not presented as model re-execution");
  assert(!document.querySelector("#investigate-comparison").hidden, "Paired policy constraints and evidence coverage are visible");
  document.querySelector("#investigate-term").value = "0";
  const comparisons = requests.filter(request => request.url.includes("/comparison")).length;
  await document.querySelector("#investigate-compare").listeners.click();
  assert(requests.filter(request => request.url.includes("/comparison")).length === comparisons, "Invalid what-if is refused before request");
  document.querySelector("#investigate-term").value = "";
  await document.querySelector("#investigate-replay").listeners.click();
  assert(document.querySelector("#investigate-playback").textContent.includes('"providerCalls": 0'), "Recorded playback displays zero calls");
  const submissions = requests.filter(request => request.url === "/api/showcase/missing").length;
  await window.launchSyntheticInvestigation("scripted-case");
  assert(requests.filter(request => request.url === "/api/showcase/missing").length === submissions, "Reconnect restores persisted case without resubmission");
  assert(location.search.includes("case=scripted-case"), "Case bookmark survives reload");
  scenario = "uncertain";
  await window.launchSyntheticInvestigation();
  assert(document.querySelector("#investigate-freeze").disabled && document.querySelector("#investigate-replay").disabled, "Uncertain evidence cannot enable freeze or replay");
  scenario = "wrong-mode";
  await window.launchSyntheticInvestigation();
  assert(document.querySelector("#investigate-status").textContent.includes("not the scripted"), "Wrong mode is refused before submission");
  scenario = "unavailable";
  await window.launchSyntheticInvestigation();
  assert(document.querySelector("#investigate-status").textContent.includes("HTTP 503"), "Backend failure is explicit");
  scenario = "wait";
  await window.launchSyntheticInvestigation();
  assert(document.querySelector("#investigate-status").textContent.includes("backend state is unknown"), "UI deadline does not fabricate completion");
  scenario = "tampered";
  await document.querySelector("#investigate-replay").listeners.click();
  assert(document.querySelector("#investigate-status").textContent.includes("Replay refused"), "Evidence refusal is surfaced");
  scenario = "delayed-replay";
  const delayed = document.querySelector("#investigate-replay").listeners.click();
  scenario = "success";
  await window.launchSyntheticInvestigation();
  releaseReplay();
  await delayed;
  assert(document.querySelector("#investigate-playback").hidden && document.querySelector("#investigate-replay").disabled,
    "Late playback response cannot overwrite a newly selected investigation");
}
