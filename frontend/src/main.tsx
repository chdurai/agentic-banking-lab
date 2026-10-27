import React, { useCallback, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "./workbench.css";

type Flow = "loan" | "incident" | "grievance" | "document";
type CaseRecord = {
  caseId: string;
  flow: Flow;
  status: string;
  version: number;
  result: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
};
type CasePage = { items: CaseRecord[]; nextCursor: string | null };
type Approval = {
  id: string;
  flow: string;
  title: string;
  status: string;
  createdAt: string;
  expiresAt?: string;
  requiredRole?: string;
  details?: string[];
};
type View = "overview" | "cases" | "reviews";
type ApiResult<T> = { data: T | null; error: string };

const flowLabels: Record<string, string> = {
  loan: "Loan review",
  incident: "Incident response",
  grievance: "Grievance redress",
  document: "Document intelligence",
};

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    headers: { Accept: "application/json" },
    credentials: "same-origin",
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const problem: unknown = await response.json();
      if (typeof problem === "object" && problem !== null && "detail" in problem
        && typeof problem.detail === "string") message = problem.detail;
    } catch {
      // Keep the HTTP status as the useful error for non-JSON responses.
    }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}

function readView(): View {
  const value = new URLSearchParams(window.location.search).get("view");
  return value === "cases" || value === "reviews" ? value : "overview";
}

function formatDate(value: string | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }).format(date);
}

function displayStatus(value: string): string {
  return value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function caseTitle(record: CaseRecord): string {
  const result = record.result;
  if (result && typeof result.title === "string") return result.title;
  if (result && typeof result.summary === "string") return result.summary;
  return record.caseId;
}

function App() {
  const [view, setView] = useState<View>(readView);
  const [cases, setCases] = useState<ApiResult<CasePage>>({ data: null, error: "" });
  const [approvals, setApprovals] = useState<ApiResult<Approval[]>>({ data: null, error: "" });
  const [selectedCase, setSelectedCase] = useState<CaseRecord | null>(null);
  const [detailError, setDetailError] = useState("");
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(true);
  const [lastUpdated, setLastUpdated] = useState("");

  const refresh = useCallback(async () => {
    setBusy(true);
    const [caseResult, approvalResult] = await Promise.allSettled([
      getJson<CasePage>("/api/v1/cases?limit=50"),
      getJson<Approval[]>("/api/approvals?status=PENDING"),
    ]);
    setCases(caseResult.status === "fulfilled"
      ? { data: caseResult.value, error: "" }
      : { data: null, error: caseResult.reason instanceof Error ? caseResult.reason.message : "Unable to load cases" });
    setApprovals(approvalResult.status === "fulfilled"
      ? { data: approvalResult.value, error: "" }
      : { data: null, error: approvalResult.reason instanceof Error ? approvalResult.reason.message : "Unable to load reviews" });
    setBusy(false);
    setLastUpdated(new Date().toISOString());
  }, []);

  useEffect(() => {
    void refresh();
    const onPopState = () => {
      setView(readView());
      setSelectedCase(null);
      setDetailError("");
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, [refresh]);

  const visibleCases = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return (cases.data?.items ?? []).filter((record) =>
      !normalized || `${record.caseId} ${record.flow} ${record.status} ${caseTitle(record)}`.toLowerCase().includes(normalized));
  }, [cases.data, query]);

  function navigate(next: View) {
    setView(next);
    setSelectedCase(null);
    setDetailError("");
    const url = new URL(window.location.href);
    if (next === "overview") url.searchParams.delete("view");
    else url.searchParams.set("view", next);
    url.searchParams.delete("case");
    window.history.pushState({}, "", url);
  }

  async function openCase(id: string) {
    setSelectedCase(null);
    setDetailError("");
    try {
      const record = await getJson<CaseRecord>(`/api/v1/cases/${encodeURIComponent(id)}`);
      setSelectedCase(record);
      const url = new URL(window.location.href);
      url.searchParams.set("view", "cases");
      url.searchParams.set("case", id);
      window.history.pushState({}, "", url);
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : "Unable to load case");
    }
  }

  const allCases = cases.data?.items ?? [];
  const pendingCases = allCases.filter((item) =>
    ["OPEN", "QUEUED", "ACCEPTED", "PENDING", "RUNNING", "IN_PROGRESS"].includes(item.status.toUpperCase())).length;
  const completedCases = allCases.filter((item) =>
    ["COMPLETED", "SUCCEEDED", "APPROVED", "REJECTED"].includes(item.status.toUpperCase())).length;

  return (
    <div className="react-app">
      <a className="skip-link" href="#workspace">Skip to workspace</a>
      <aside className="sidebar" aria-label="Primary navigation">
        <a className="brand" href="/"><span className="brand-mark" aria-hidden="true">✦</span>
          <span><strong>northstar</strong><small>AGENTIC BANKING</small></span></a>
        <p className="nav-caption">REACT WORKBENCH · PREVIEW</p>
        <nav className="nav-list" aria-label="Workbench views">
          <button className={view === "overview" ? "nav-link active" : "nav-link"} onClick={() => navigate("overview")}>◫ <span>Overview</span></button>
          <button className={view === "cases" ? "nav-link active" : "nav-link"} onClick={() => navigate("cases")}>▤ <span>Cases</span><b>{cases.data?.items.length ?? "—"}</b></button>
          <button className={view === "reviews" ? "nav-link active" : "nav-link"} onClick={() => navigate("reviews")}>◇ <span>Review queue</span><b>{approvals.data?.length ?? "—"}</b></button>
          <a className="nav-link" href="/mission-control.html">◎ <span>Mission Control</span></a>
        </nav>
        <div className="sidebar-foot"><span className="online-dot" /> Demo API · synthetic data</div>
      </aside>

      <main id="workspace" className="main">
        <header className="topbar">
          <div><p className="eyebrow">AGENTIC BANKING / REACT PREVIEW</p>
            <h1>{view === "overview" ? "Operations overview" : view === "cases" ? "Case workspace" : "Human review queue"}</h1></div>
          <div className="actions">
            <span className="environment">DEVELOPMENT</span>
            <button className="button secondary" onClick={() => void refresh()} disabled={busy}>↻ Refresh</button>
            <a className="button secondary" href="/">Full workbench</a>
          </div>
        </header>

        <section className="notice" role="note">
          <strong>React + TypeScript migration preview.</strong> This case explorer reads live Spring Boot APIs.
          Use the full workbench for case creation and reviewer decisions; those screens remain on the existing UI during migration.
        </section>

        {(cases.error || approvals.error) && <section className="error" role="alert">
          <strong>Some workspace data is unavailable.</strong>
          {cases.error && <p>Cases: {cases.error}</p>}
          {approvals.error && <p>Review queue: {approvals.error}</p>}
          <p>The preview shows API errors rather than substituting sample records.</p>
        </section>}

        {view === "overview" && <section className="metrics" aria-label="Workspace summary">
          <Metric label="Cases loaded" value={cases.data ? String(allCases.length) : "—"} />
          <Metric label="In progress" value={cases.data ? String(pendingCases) : "—"} />
          <Metric label="Awaiting review" value={approvals.data ? String(approvals.data.length) : "—"} />
          <Metric label="Completed" value={cases.data ? String(completedCases) : "—"} />
        </section>}

        <section className="panel">
          <div className="panel-heading">
            <div><p className="eyebrow">{view === "reviews" ? "HUMAN OVERSIGHT" : "LIVE API DATA"}</p>
              <h2>{view === "reviews" ? "Pending review items" : selectedCase ? "Case details" : view === "overview" ? "Recent cases" : "Cases"}</h2>
              <p>{lastUpdated ? `Last refreshed ${formatDate(lastUpdated)}` : "Connecting to the Spring Boot API"}</p></div>
            {view === "cases" && !selectedCase && <label className="search">
              <span className="sr-only">Search cases</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search cases" type="search" />
            </label>}
            {selectedCase && <button className="button secondary" onClick={() => { setSelectedCase(null); setDetailError(""); }}>← Back to cases</button>}
          </div>
          {busy && !cases.data && <p className="loading" role="status">Loading workspace data…</p>}
          {detailError && <p className="error" role="alert">Could not open case: {detailError}</p>}
          {selectedCase ? <CaseDetail record={selectedCase} /> : view === "reviews"
            ? <ApprovalList data={approvals.data} error={approvals.error} />
            : <CaseList records={view === "overview" ? visibleCases.slice(0, 6) : visibleCases} error={cases.error} onOpen={openCase} />}
        </section>

        <footer className="footer"><span>Northstar · Agentic Banking</span><span>AI assists. People and policy stay in control.</span></footer>
      </main>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return <article className="metric"><span>{label}</span><strong>{value}</strong><small>Current caller scope</small></article>;
}

function CaseList({ records, error, onOpen }: { records: CaseRecord[]; error: string; onOpen: (id: string) => void }) {
  if (error) return <p className="empty">Case results are unavailable because the API request failed.</p>;
  if (!records.length) return <p className="empty">No visible cases were returned by the API.</p>;
  return <div className="table-scroll"><table>
    <thead><tr><th>Case</th><th>Workflow</th><th>Status</th><th>Updated</th><th /></tr></thead>
    <tbody>{records.map((record) => <tr key={record.caseId}>
      <td><button className="case-link" onClick={() => onOpen(record.caseId)}>{caseTitle(record)}</button><small>{record.caseId}</small></td>
      <td>{flowLabels[record.flow] ?? record.flow}</td>
      <td><span className={`status ${record.status.toLowerCase().replace(/[^a-z0-9_-]/g, "")}`}>{displayStatus(record.status)}</span></td>
      <td>{formatDate(record.updatedAt || record.createdAt)}</td>
      <td><button className="text-button" onClick={() => onOpen(record.caseId)}>Open →</button></td>
    </tr>)}</tbody>
  </table></div>;
}

function ApprovalList({ data, error }: { data: Approval[] | null; error: string }) {
  if (error) return <p className="empty">Review items are unavailable because the API request failed.</p>;
  if (!data?.length) return <p className="empty">No pending review items were returned by the API.</p>;
  return <div className="approval-list">{data.map((item) => <article className="approval" key={item.id}>
    <div><span className="eyebrow">{flowLabels[item.flow] ?? item.flow}</span><h3>{item.title || "Review requested"}</h3>
      <p>{item.details?.slice(0, 2).join(" · ") || `Required role: ${item.requiredRole || "assigned reviewer"}`}</p>
      <small>Opened {formatDate(item.createdAt)} · Expires {formatDate(item.expiresAt)}</small></div>
      <span className="status pending">Pending review</span>
  </article>)}</div>;
}

function CaseDetail({ record }: { record: CaseRecord }) {
  return <div className="case-detail">
    <div className="detail-meta"><span>{flowLabels[record.flow] ?? record.flow}</span><span>{displayStatus(record.status)}</span><span>Version {record.version}</span></div>
    <p className="detail-note">Outcome returned by the backend. Model-generated summaries are not independently verified facts.</p>
    {record.result
      ? <pre>{JSON.stringify(record.result, null, 2)}</pre>
      : <p className="empty">No outcome has been recorded yet. Do not resubmit the case.</p>}
  </div>;
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
