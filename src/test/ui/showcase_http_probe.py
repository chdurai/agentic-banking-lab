"""Independent real HTTP contracts; not a substitute for browser verification."""
import argparse
import json
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener
import uuid


def run(base):
    address = urlsplit(base)
    assert address.scheme == "http" and address.hostname in ("127.0.0.1", "localhost")
    client = build_opener()

    def request(path, data=None, headers=None, method=None, expected=200):
        payload = None if data is None else json.dumps(data).encode()
        req = Request(base + path, data=payload, method=method,
                      headers={"Content-Type": "application/json", **(headers or {})})
        try:
            response = client.open(req, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            text = response.read().decode()
            assert response.status == expected, (path, response.status, text)
            return json.loads(text) if text else None

    mode = request("/api/showcase")
    assert mode["mode"] == "SCRIPTED" and mode["data"] == "SYNTHETIC_ONLY"
    cases = []
    for scenario, tool in (("complete", None), ("missing", "loan.documents.v1"), ("conflicting", "loan.sources.v1")):
        key = str(uuid.uuid4())
        submitted = request("/api/showcase/" + scenario, {}, {"Idempotency-Key": key})
        case_id = submitted["caseId"]
        same = request("/api/showcase/" + scenario, {}, {"Idempotency-Key": key})
        assert same["caseId"] == case_id
        until = time.monotonic() + 60
        while time.monotonic() < until:
            row = request("/api/v1/cases/" + case_id)
            if row["status"] not in ("OPEN", "RUNNING"):
                break
            time.sleep(0.1)
        assert row["status"] == "PENDING_APPROVAL", row
        assert row["result"]["decision"] == "MANUAL_REVIEW", row
        reports = row["result"]["agentReports"]
        assert {"intake", "kyc", "credit-risk", "compliance", "loan-investigator"} <= {r["agentName"] for r in reports}
        root = f"/api/v1/cases/{case_id}/investigation"
        journal = request(root)
        attempts = journal["attempts"]
        assert all(a["state"] == "SUCCEEDED" for a in attempts), attempts
        assert [a["tool_name"] for a in attempts if a["kind"] == "TOOL"] == ([] if tool is None else [tool])
        passport = request(root + "/passport", {}, method="POST")
        assert passport["integrity"] == "CONTENT_CONSISTENCY_ONLY"
        before = request("/api/v1/cases/" + case_id)
        replay = request(root + "/replay")
        assert all(replay[field] == 0 for field in ("providerCalls", "toolCalls", "mutationCalls"))
        assert replay["passport"] == passport
        after = request("/api/v1/cases/" + case_id)
        assert before == after, "Recorded playback changed original case"
        request(root, headers={"X-Tenant-Id": "foreign"}, expected=403)
        if scenario == "complete":
            approvals = request("/api/approvals")
            run_id = journal["run"]["run_id"]
            approval = next(a for a in approvals if any(
                f"run={run_id}" in finding["citations"]
                for finding in a["evidencePacket"]["agentFindings"]
            ))
            approved = request(f"/api/v1/approvals/{approval['id']}/decisions",
                               {"decision": "APPROVE", "reasonCode": "SYNTHETIC_REVIEW", "comment": "Explicit test reviewer authorization"},
                               {"If-Match": f'"{approval["version"]}"'})
            receipt = approved["executionLog"]
            assert any("receipt=sim-" in entry for entry in receipt), approved
            reconciled = request(f"/api/approvals/{approval['id']}/reconcile", {}, method="POST")
            assert reconciled["executionLog"] == receipt
            # Even after a reviewer decision, playback itself must not refresh the original case lifecycle.
            request(root + "/replay")
        cases.append({"scenario": scenario, "caseId": case_id, "optionalTools": tool, "passed": True})
    request("/api/showcase/not-a-scenario", {}, {"Idempotency-Key": str(uuid.uuid4())}, expected=400)
    request("/api/v1/cases/no-such-case/investigation", expected=404)
    report = {"mode": mode, "createdAtEpoch": time.time(), "cases": cases,
              "checks": ["mandatory specialist reports", "review routing", "idempotent submission",
                         "scope refusal", "passport", "zero-call replay", "explicit reviewer authorization",
                         "stable simulation receipt"], "browserVerified": False}
    Path("target").mkdir(exist_ok=True)
    Path("target/showcase-http-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: real HTTP showcase, mandatory reports, review, passport, replay and stable simulated receipt")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    run(parser.parse_args().base_url.rstrip("/"))
