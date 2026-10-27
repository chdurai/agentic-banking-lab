"use strict";

// Opt-in URL keeps the normal live-provider workbench and its launch behavior unchanged.
if (new URLSearchParams(location.search).get("showcase") === "1") {
  const panel = document.querySelector("#investigation-panel");
  const status = document.querySelector("#investigate-status");
  const launch = document.querySelector("#investigate-launch");
  const cancel = document.querySelector("#investigate-cancel");
  const replay = document.querySelector("#investigate-replay");
  const freeze = document.querySelector("#investigate-freeze");
  const compare = document.querySelector("#investigate-compare");
  const comparisonPanel = document.querySelector("#investigate-comparison");
  const outcome = document.querySelector("#investigate-outcome");
  const outcomePanel = document.querySelector("#investigate-review-outcome");
  const timeline = document.querySelector("#investigate-timeline");
  const playback = document.querySelector("#investigate-playback");
  let caseId = null;
  let generation = 0;
  panel.hidden = false;

  async function request(url, options) {
    const response = await fetch(url, {credentials: "same-origin", ...options});
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${url}`);
    return response.json();
  }

  window.launchSyntheticInvestigation = async function (resumeCaseId) {
    if (launch.disabled) return;
    const current = ++generation;
    launch.disabled = true;
    replay.disabled = true;
    freeze.disabled = true;
    compare.disabled = true;
    outcome.disabled = true;
    cancel.disabled = true;
    playback.hidden = true;
    comparisonPanel.hidden = true;
    outcomePanel.hidden = true;
    timeline.replaceChildren();
    status.textContent = "Submitting synthetic case; mandatory specialist checks run first.";
    try {
      const mode = await request("/api/showcase");
      if (mode.mode !== "SCRIPTED" || mode.data !== "SYNTHETIC_ONLY") {
        throw new Error("Backend is not the scripted synthetic showcase");
      }
      const scenario = document.querySelector("#showcase-scenario").value;
      const accepted = typeof resumeCaseId === "string" ? {caseId: resumeCaseId} : await request(`/api/showcase/${scenario}`, {
        method: "POST", headers: {"Idempotency-Key": crypto.randomUUID()}
      });
      caseId = accepted.caseId;
      history.replaceState(null, "", `?showcase=1&case=${encodeURIComponent(caseId)}`);
      document.querySelector("#investigate-review").href = `/#case/${encodeURIComponent(caseId)}`;
      const deadline = Date.now() + 150000;
      while (current === generation && Date.now() < deadline) {
        const record = await request(`/api/v1/cases/${encodeURIComponent(caseId)}`);
        const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/investigation`);
        if (response.ok) {
          const investigation = await response.json();
          const run = investigation.run;
          const models = investigation.attempts.filter(attempt => attempt.kind === "MODEL");
          const usages = models.filter(attempt => attempt.state === "SUCCEEDED").map(attempt => JSON.parse(attempt.payload));
          const total = field => usages.reduce((sum, usage) => sum + Number(usage[field]), 0);
          document.querySelector("#investigate-budget").textContent =
            `${models.length}/3 proposals | ${investigation.attempts.filter(attempt => attempt.kind === "TOOL").length}/2 optional tools | ` +
            `${total("providerCalls")} scripted exchanges | ${total("promptTokens") + total("completionTokens")} synthetic tokens | ` +
            `$${total("estimatedCostUsd").toFixed(4)} estimated (no provider charge)` +
            (models.some(attempt => attempt.state !== "SUCCEEDED") ? " | usage incomplete/uncertain; retained reservations are not shown here" : "");
          cancel.disabled = run.state !== "RUNNING";
          status.textContent = `${caseId} | ${record.status} | investigation ${run.state} | gap ${run.gap} | limits: 3 proposals / 2 tools / 30s`;
          timeline.replaceChildren(...investigation.attempts.map(attempt => {
            const item = document.createElement("li");
            item.textContent = `${attempt.ordinal_no}. ${attempt.kind} ${attempt.tool_name}: ${attempt.state} | ${attempt.attempt_id}`;
            const details = document.createElement("details");
            const summary = document.createElement("summary");
            summary.textContent = "Persisted evidence / usage";
            const content = document.createElement("pre");
            content.style.whiteSpace = "pre-wrap";
            content.textContent = attempt.payload || "No observation persisted. Never assume success or replay.";
            details.append(summary, content);
            item.append(details);
            return item;
          }));
          freeze.disabled = !record.result || !["COMPLETE", "REVIEW_REQUIRED", "NO_ALLOWED_EVIDENCE"].includes(run.state) ||
            investigation.attempts.some(attempt => attempt.state !== "SUCCEEDED");
        } else if (response.status !== 404) {
          throw new Error(`Investigation unavailable: HTTP ${response.status}`);
        }
        if (!["OPEN", "RUNNING"].includes(record.status)) {
          status.textContent += ` | recommendation: ${record.result?.decision || record.status} (not an executed effect)`;
          break;
        }
        await new Promise(resolve => setTimeout(resolve, 400));
      }
      if (Date.now() >= deadline) throw new Error("UI wait expired; backend state is unknown. Inspect the persisted case before retrying.");
    } catch (error) {
      status.textContent = `Investigation not confirmed: ${error.message}`;
    } finally {
      launch.disabled = false;
    }
  };
  launch.addEventListener("click", window.launchSyntheticInvestigation);
  cancel.addEventListener("click", async () => {
    try {
      await request(`/api/v1/cases/${encodeURIComponent(caseId)}/investigation/cancel`, {method: "POST"});
      status.textContent = "Cancellation requested, not yet confirmed. Pending attempts must not be replayed.";
      cancel.disabled = true;
    } catch (error) { status.textContent = `Cancellation failed: ${error.message}`; }
  });
  replay.addEventListener("click", async () => {
    const current = generation;
    const selectedCase = caseId;
    playback.hidden = true;
    try {
      const result = await request(`/api/v1/cases/${encodeURIComponent(selectedCase)}/investigation/replay`);
      if (current !== generation) return;
      playback.textContent = JSON.stringify(result, null, 2);
      playback.hidden = false;
    } catch (error) { if (current === generation) status.textContent = `Replay refused: ${error.message}`; }
  });
  freeze.addEventListener("click", async () => {
    const current = generation;
    const selectedCase = caseId;
    playback.hidden = true;
    replay.disabled = true;
    compare.disabled = true;
    outcome.disabled = true;
    try {
      const result = await request(`/api/v1/cases/${encodeURIComponent(selectedCase)}/investigation/passport`, {method: "POST"});
      if (current !== generation) return;
      playback.textContent = JSON.stringify(result, null, 2);
      playback.hidden = false;
      replay.disabled = false;
      compare.disabled = false;
      outcome.disabled = false;
    } catch (error) { if (current === generation) status.textContent = `Passport refused: ${error.message}`; }
  });
  outcome.addEventListener("click", async () => {
    const selectedCase = caseId;
    const current = generation;
    outcomePanel.hidden = true;
    outcome.disabled = true;
    try {
      const result = await request(`/api/v1/cases/${encodeURIComponent(selectedCase)}/investigation/review-outcome`);
      if (current !== generation) return;
      if (result.mode !== "READ_ONLY_CURRENT_REVIEW_OUTCOME" || result.caseId !== selectedCase ||
          !Array.isArray(result.reviews) || !result.passportSha256) {
        throw new Error("Backend review outcome is incomplete or belongs to a different case");
      }
      const heading = document.createElement("h3");
      heading.textContent = "Human authority and simulated effect (read-only refresh)";
      const items = result.reviews.map(review => {
        const item = document.createElement("p");
        item.textContent = `Review ${review.approvalId}: ${review.reviewStatus} | ${review.effectState}` +
          (review.receipt ? ` | local receipt ${review.receipt.receiptId} | evidence ${review.receipt.approvalEvidenceSha256}` : " | no receipt recorded");
        return item;
      });
      const explanation = document.createElement("p");
      explanation.textContent = `Observed ${result.observedAt}. Bound to passport ${result.passportSha256}. ` +
        "This later outcome does not rewrite frozen analysis. Approval alone is not an effect; no funds moved. " +
        (items.length ? "Refresh does not execute or reconcile an action." : "No reviewer checkpoint is recorded.");
      outcomePanel.replaceChildren(heading, ...items, explanation);
      outcomePanel.hidden = false;
    } catch (error) {
      if (current === generation) status.textContent = `Review outcome not confirmed: ${error.message}`;
    } finally {
      if (current === generation) outcome.disabled = false;
    }
  });
  compare.addEventListener("click", async () => {
    const current = generation;
    const selectedCase = caseId;
    comparisonPanel.hidden = true;
    playback.hidden = true;
    try {
      const term = document.querySelector("#investigate-term").value.trim();
      if (term && (!/^\d+$/.test(term) || Number(term) < 6 || Number(term) > 360)) {
        throw new Error("Hypothetical term must be an integer between 6 and 360 months");
      }
      const result = await request(`/api/v1/cases/${encodeURIComponent(selectedCase)}/investigation/comparison` +
        (term ? `?termMonths=${encodeURIComponent(term)}` : ""));
      if (current !== generation) return;
      if (result.mode !== "FROZEN_POLICY_COMPARISON_NOT_MODEL_REEXECUTION" ||
          !result.baseline?.envelope || !result.investigated?.envelope || !Array.isArray(result.findings)) {
        throw new Error("Backend comparison is incomplete or is not frozen policy-only mode");
      }
      const heading = document.createElement("h3");
      heading.textContent = "Decision laboratory: same frozen evidence, zero new model/tool/action calls";
      const table = document.createElement("table");
      table.setAttribute("aria-label", "Paired frozen policy floors");
      for (const [label, envelope] of [
        ["Mandatory specialists only", result.baseline.envelope],
        ["With recorded investigator finding", result.investigated.envelope],
        ...(result.whatIf ? [[`Policy-only term sensitivity: ${result.whatIf.termMonths} months`, result.whatIf.envelope]] : [])
      ]) {
        const row = document.createElement("tr");
        const name = document.createElement("th");
        name.setAttribute("scope", "row");
        name.textContent = label;
        const value = document.createElement("td");
        value.textContent = `${envelope.floor} | estimated APR ${envelope.apr}% | ${envelope.internalReasonCodes.join(", ") || "No stricter policy constraint"}`;
        row.append(name, value);
        table.append(row);
      }
      const coverage = document.createElement("p");
      coverage.textContent = result.findings.map(finding => `${finding.agent}: ${finding.coverage}`).join(" | ");
      const caveat = document.createElement("p");
      caveat.textContent = "Policy floors are constraints, not approval or executed effects. Scripted findings are synthetic; " +
        "agent-level evidence links do not establish real customer verification. What-if retains all original specialist findings.";
      comparisonPanel.replaceChildren(heading, table, coverage, caveat);
      comparisonPanel.hidden = false;
      playback.textContent = JSON.stringify(result, null, 2);
      playback.hidden = false;
    } catch (error) { if (current === generation) status.textContent = `Comparison refused: ${error.message}`; }
  });
  const savedCase = new URLSearchParams(location.search).get("case");
  if (savedCase) window.launchSyntheticInvestigation(savedCase);
}
