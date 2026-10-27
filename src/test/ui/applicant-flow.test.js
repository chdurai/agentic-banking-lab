// Dependency-free assertions; run with a JavaScript engine after loading app.js and this DOM fixture.
const elements = new Map();
function element(selector) {
  if (!elements.has(selector)) elements.set(selector, {
    value: "", innerHTML: "", textContent: "", hidden: false, disabled: false, checked: true,
    isConnected: true, classList: { add() {}, remove() {}, toggle() {} },
    querySelector: child => element(`${selector} ${child}`), setAttribute() {}, removeAttribute() {},
    addEventListener() {}, reportValidity: () => true, reset() {}, close() {}, showModal() {}
  });
  return elements.get(selector);
}
globalThis.document = { querySelector: element, querySelectorAll: () => [], addEventListener() {} };
globalThis.window = { location: { hash: "" }, addEventListener() {}, setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {}, scrollTo() {} };
globalThis.Intl = {
  NumberFormat: function () { return { format: value => `$${value}` }; },
  DateTimeFormat: function () { return { format: value => String(value) }; }
};
let uuid = 0;
globalThis.crypto = { randomUUID: () => `intent-${++uuid}` };
if (!Object.hasOwn) Object.hasOwn = (object, property) => Object.prototype.hasOwnProperty.call(object, property);
let formValues = {};
globalThis.FormData = function () { this.get = name => formValues[name] ?? null; };
globalThis.testResults = [];
function assert(condition, message) {
  if (!condition) throw new Error(message);
  testResults.push(message);
}

globalThis.runApplicantTests = async function () {
  const profile = { applicantId: "A001", fullName: "<Jane>", annualIncome: 145000,
    employmentYears: 8, employer: "Acme", existingMonthlyDebt: 1200, documents: ["id.pdf"],
    source: "Synthetic demo", verificationStatus: "SIMULATED_RECORD", recordedAt: "2026-10-23", version: 1 };
  api = async () => [profile];
  showNewCase("loan");
  await Promise.resolve();
  assert(element("#flow-fields").innerHTML.includes("applicant-search"), "Loan journey starts with applicant search");
  assert(element("#submit-case").hidden, "No loan can submit before applicant selection");
  api = async () => profile;
  await selectApplicant("A001");
  assert(loanWizard.step === "profile", "Selecting an applicant loads their stored profile");
  assert(element("#flow-fields").innerHTML.includes("&lt;Jane&gt;"), "Untrusted profile text is escaped");
  assert(!element("#flow-fields").innerHTML.includes('name="ssnLast4"'), "Existing applicants never re-enter SSN");
  loanWizard.step = "details";
  renderLoanFlow();
  assert(element("#flow-fields").innerHTML.includes('name="requestedAmount"'), "Loan details collect application-specific fields");
  assert(!element("#flow-fields").innerHTML.includes('name="annualIncome"'), "Stored income is not re-entered");
  formValues = { requestedAmount: "25000", loanPurpose: "Home repair", termMonths: "60" };
  element("#flow-select").value = "loan";
  await submitCase({ preventDefault() {}, currentTarget: element("#case-form") });
  assert(loanWizard.step === "confirm", "Loan details lead to explicit confirmation");
  assert(element("#flow-fields").innerHTML.includes("loan-confirmed"), "Confirmation requires a review checkbox");
  const requests = [];
  api = async (path, options) => { requests.push({path, options}); throw new Error("Temporary network failure"); };
  await submitCase({ preventDefault() {}, currentTarget: element("#case-form") });
  await submitCase({ preventDefault() {}, currentTarget: element("#case-form") });
  assert(requests[0].path === "/api/loans/applicants/A001/cases", "Submission uses the applicant-backed endpoint");
  const body = JSON.parse(requests[0].options.body);
  assert(body.profileVersion === 1 && body.confirmed && !Object.hasOwn(body, "annualIncome"), "Submission sends profile version and loan details, not browser-supplied profile data");
  assert(requests[0].options.headers["Idempotency-Key"] === requests[1].options.headers["Idempotency-Key"], "Uncertain submission retries reuse the same idempotency key");
  assert(!submissionInFlight && !element("#flow-select").disabled, "Failed submission restores interactive controls");
  loanWizard.details.requestedAmount = 30000;
  await submitCase({ preventDefault() {}, currentTarget: element("#case-form") });
  assert(requests[2].options.headers["Idempotency-Key"] !== requests[1].options.headers["Idempotency-Key"], "Changed application details create a new submission intent");
  loanWizard.step = "onboard";
  renderLoanFlow();
  assert(element("#flow-fields").innerHTML.includes('name="ssnLast4"'), "New applicants have a separate onboarding form");
  formValues = { fullName: "Taylor Demo", ssnLast4: "1234", employer: "Demo Works",
    annualIncome: "85000", employmentYears: "4", existingMonthlyDebt: "400" };
  api = async () => ({ ...profile, applicantId: "DEMO-new", fullName: "Taylor Demo", documents: [] });
  await onboardApplicant(element("#save-applicant"));
  assert(loanWizard.step === "profile" && loanWizard.profile.applicantId === "DEMO-new", "Onboarding creates a reusable profile and returns to review");
  loanWizard.step = "confirm";
  loanWizard.details = { requestedAmount: 25000, loanPurpose: "Repair", termMonths: 60 };
  let submitCount = 0;
  let resolveSubmission;
  api = () => { submitCount++; return new Promise(resolve => { resolveSubmission = resolve; }); };
  const savedRender = render;
  const savedLoad = loadData;
  render = () => {};
  loadData = async () => { state.cases = []; };
  const firstSubmission = submitCase({ preventDefault() {}, currentTarget: element("#case-form") });
  await submitCase({ preventDefault() {}, currentTarget: element("#case-form") });
  assert(submitCount === 1, "Double submission is blocked while the first request is pending");
  resolveSubmission({ caseId: "accepted-case", flow: "loan", status: "OPEN" });
  await firstSubmission;
  assert(state.view === "case-detail" && state.selectedCaseId === "accepted-case"
    && state.cases[0].caseId === "accepted-case", "Accepted cases open directly even if absent from the first list page");
  render = savedRender;
  loadData = savedLoad;
  let pollCount = 0;
  window.setInterval = () => { pollCount++; return 1; };
  startCasePolling();
  assert(pollCount === 1, "Actual backend OPEN status starts progress polling");
  state.cases[0].status = "COMPLETED";
  startCasePolling();
  assert(pollCount === 1, "Completed cases stop automatic progress polling");
  const loanResult = readableResult({flow:"loan", result:{decision:"DECLINED", reasoning:"<script>bad</script>",
    adverseActionReasons:["High debt"], approvedAmount:0, interestRateApr:null,
    agentReports:[{agentName:"Credit",status:"WARN",summary:"Check completed",findings:["Review debt"],redFlags:[]}]}});
  assert(loanResult.includes("Declined") && loanResult.includes("High debt") && loanResult.includes("Credit"),
    "Loan outcomes show reasons and agent findings in readable text");
  assert(!loanResult.includes("<script>") && loanResult.includes("&lt;script&gt;"), "Outcome summaries escape model-generated text");
  const docResult = readableResult({flow:"document",result:{status:"ANALYZED",analysis:{
    documentType:"INVOICE", summary:"Sample invoice",keyFields:{vendor:"Demo Supplier"},confidencePct:83}}});
  assert(docResult.includes("Demo Supplier") && docResult.includes("not a guarantee"), "Document outcomes show extracted fields and honest confidence labels");
  assert(nextStep({status:"COMPLETED"}).includes("does not necessarily mean"), "Completion is distinguished from a loan approval");
  assert(nextStep({status:"PENDING_APPROVAL"}).includes("review queue"), "Pending cases direct users to human review");
  const closedPacket = approvalDetailView({id:"review",flow:"loan",status:"APPROVED",version:2});
  assert(!closedPacket.includes('id="open-decision"') && closedPacket.includes("Review already closed"),
    "Closed review packets cannot offer another final decision");
  const blindPacket = approvalDetailView({id:"review",flow:"loan",status:"PENDING",version:1,blindFirstRequired:true,
    evidencePacket:{aiRecommendation:"SECRET_RECOMMENDATION",agentFindings:[{summary:"SECRET_FINDING"}]}});
  assert(blindPacket.includes("Independent assessment first") && !blindPacket.includes("SECRET_RECOMMENDATION")
    && !blindPacket.includes("SECRET_FINDING"), "Blind-first review offers an independent step without revealing AI recommendations");
  let sampleCall = "";
  const sampleFields = new Map();
  element("#case-form").elements = {namedItem: name => {
    if (!sampleFields.has(name)) sampleFields.set(name,{value:"",type:"text"});
    return sampleFields.get(name);
  }};
  element("#flow-select").value = "incident";
  api = async path => { sampleCall=path; return {incidentId:"INC-1042", title:"Database incident",tags:["database","latency"]}; };
  await loadDemoSample(element("#load-demo-sample"));
  assert(sampleCall === "/api/incidents/sample" && sampleFields.get("title").value === "Database incident",
    "Demo examples load from the existing backend rather than invented results");
  assert(sampleFields.get("incidentId").value.startsWith("DEMO-") && sampleFields.get("tags").value === "database, latency",
    "Sample form inputs receive new references and formatted tags");
  let fetchedRoute = "";
  state.view="overview"; state.cases=[];
  window.location.hash="#case/recovered-case";
  api=async path=>{fetchedRoute=path;return {caseId:"recovered-case",flow:"loan",status:"COMPLETED"};};
  await restoreRoute();
  assert(fetchedRoute === "/api/v1/cases/recovered-case" && state.selectedCaseId === "recovered-case"
    && state.view === "case-detail", "Case URLs recover their record directly after browser refresh");
  state.nextCursor="cursor-one"; state.cases=[{caseId:"one"}]; state.view="cases";
  api=async()=>({items:[{caseId:"one"},{caseId:"two"}],nextCursor:null});
  await loadMoreCases(element("#load-more-cases"));
  assert(state.cases.length===2 && state.nextCursor===null, "Case pagination appends unique records and ends at the final page");
  element("#reason-code").value="DEMO_REVIEWED";
  element("#decision-comment").value="Demo evidence reviewed";
  state.selectedApproval={id:"review-1",flow:"loan",version:3,status:"PENDING",blindFirstRequired:true};
  const approvalRequests=[];
  api=async(path,options)=>{
    approvalRequests.push({path,options});
    return path.endsWith("blind-first") ? {status:"PENDING"}
      : {id:"review-1",flow:"loan",version:4,status:"PENDING",blindFirstRequired:true,
        blindFirstAssessment:{decision:"APPROVE"},evidencePacket:{aiRecommendation:"Review me"}};
  };
  await submitDecision("APPROVE");
  assert(approvalRequests[0].path==="/api/approvals/review-1/blind-first"
    && approvalRequests.every(call=>!call.path.includes("/decisions")), "Independent assessment never executes a final decision");
  assert(state.selectedApproval.version===4 && state.selectedApproval.blindFirstAssessment,
    "Independent assessment refreshes the authoritative packet before final review");
  const realLoad=loadData;
  loadData=async()=>{};
  api=async(path,options)=>{approvalRequests.push({path,options});return {status:"APPROVED"};};
  await submitDecision("APPROVE");
  const finalCall=approvalRequests[approvalRequests.length-1];
  assert(finalCall.path==="/api/v1/approvals/review-1/decisions"
    && finalCall.options.headers["If-Match"]==='"4"', "Final decisions bind to the newly refreshed packet version");
  assert(state.view==="approvals" && !state.selectedApproval && !decisionInFlight, "Successful decisions clear selection and return to the queue");
  state.selectedApproval={id:"review-2",flow:"loan",version:1,status:"PENDING"};
  api=async(path,options)=>{
    if(options?.method==="POST") {const error=new Error("Stale packet");error.status=412;throw error;}
    return {id:"review-2",flow:"loan",version:2,status:"PENDING"};
  };
  await submitDecision("REJECT");
  assert(state.selectedApproval.version===2 && !decisionInFlight, "Stale decisions reload current evidence and release the submission lock");
  loadData=realLoad;
  const incidentResult=readableResult({flow:"incident",result:{status:"ESCALATED",
    diagnosis:{probableCauses:["Database pool exhausted"],evidence:["Pool at limit"],matchedRunbooks:["RB-DB-001"]},
    remediation:{steps:[{action:"restart",riskLevel:"HIGH",requiresApproval:true}],rollbackPlan:"Restore previous config"}}});
  assert(incidentResult.includes("Database pool exhausted") && incidentResult.includes("human approval required")
    && incidentResult.includes("simulated"), "Incident results explain causes, proposed actions, and demo-only execution");
  const grievanceResult=readableResult({flow:"grievance",result:{status:"ROUTED",route:"PROJECT_GRM_OFFICER",slaHours:168,
    responseDraft:{acknowledgement:"Concern received",proposedActions:["Contact officer"],citedPlaybooks:["GRM-001"],followUpPlan:"Review next week"}}});
  assert(grievanceResult.includes("Draft acknowledgement (not sent)") && grievanceResult.includes("Contact officer"),
    "Grievance results distinguish an unsent draft from a completed communication");
};
