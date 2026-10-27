"""Real scripted showcase, no API stubs; opt in with BROWSER_TEST_BASE_URL."""
import os
import time
import uuid
from urllib.parse import urlsplit

import pytest
from playwright.sync_api import expect

pytestmark = pytest.mark.skipif(
    not os.environ.get("BROWSER_TEST_BASE_URL"),
    reason="Requires the local scripted showcase backend",
)


@pytest.mark.parametrize("scenario,tool", [
    ("complete", None),
    ("missing", "loan.documents.v1"),
    ("conflicting", "loan.sources.v1"),
])
def test_scripted_investigation_passport_and_replay(page, scenario, tool):
    base = os.environ["BROWSER_TEST_BASE_URL"]
    assert urlsplit(base).hostname in ("127.0.0.1", "localhost")
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(base + "/mission-control.html?showcase=1")
    expect(page.locator("#showcase-mode")).to_contain_text("SCRIPTED")
    page.get_by_label("Versioned loan scenario").select_option(scenario)
    page.get_by_role("button", name="Launch investigation", exact=True).click()
    expect(page.locator("#investigate-status")).to_contain_text("recommendation: MANUAL_REVIEW", timeout=60000)
    if tool:
        expect(page.locator("#investigate-timeline")).to_contain_text(tool)
        expect(page.locator("#investigate-timeline")).to_contain_text("SUCCEEDED")
    else:
        expect(page.locator("#investigate-timeline")).not_to_contain_text("TOOL")
        expect(page.locator("#investigate-budget")).to_contain_text("0/3 proposals")
    page.get_by_role("button", name="Freeze evidence passport").click()
    expect(page.locator("#investigate-playback")).to_contain_text("CONTENT_CONSISTENCY_ONLY")
    page.get_by_role("button", name="Recorded replay (zero calls)").click()
    expect(page.locator("#investigate-playback")).to_contain_text('"providerCalls": 0')
    expect(page.locator("#investigate-playback")).to_contain_text('"toolCalls": 0')
    expect(page.locator("#investigate-playback")).to_contain_text('"mutationCalls": 0')
    page.locator("#investigate-term").fill("120")
    page.get_by_role("button", name="Compare frozen policy floors").click()
    expect(page.locator("#investigate-playback")).to_contain_text("FROZEN_POLICY_COMPARISON_NOT_MODEL_REEXECUTION")
    expect(page.locator("#investigate-playback")).to_contain_text('"floor": "DECLINED"')
    page.reload()
    expect(page.locator("#connection-label")).to_have_text("live", timeout=15000)
    assert not errors


@pytest.mark.parametrize("scenario,expected", [
    ("fault-hostile", "REJECTED"),
    ("fault-duplicate", "SUCCEEDED"),
    ("fault-timeout", "UNCERTAIN"),
])
def test_synthetic_fault_controls(page, scenario, expected):
    base = os.environ["BROWSER_TEST_BASE_URL"]
    assert urlsplit(base).hostname in ("127.0.0.1", "localhost")
    page.goto(base + "/mission-control.html?showcase=1")
    page.get_by_label("Versioned loan scenario").select_option(scenario)
    page.get_by_role("button", name="Launch investigation", exact=True).click()
    expect(page.locator("#investigate-status")).to_contain_text("recommendation: MANUAL_REVIEW", timeout=60000)
    expect(page.locator("#investigate-timeline")).to_contain_text(expected)
    if scenario != "fault-duplicate":
        expect(page.locator("#investigate-freeze")).to_be_disabled()


def test_synthetic_cancellation_window(page):
    base = os.environ["BROWSER_TEST_BASE_URL"]
    assert urlsplit(base).hostname in ("127.0.0.1", "localhost")
    page.goto(base + "/mission-control.html?showcase=1")
    page.get_by_label("Versioned loan scenario").select_option("fault-cancel")
    page.get_by_role("button", name="Launch investigation", exact=True).click()
    expect(page.locator("#investigate-cancel")).to_be_enabled(timeout=30000)
    page.locator("#investigate-cancel").click()
    expect(page.locator("#investigate-status")).to_contain_text("investigation CANCELLED", timeout=30000)
    expect(page.locator("#investigate-timeline")).to_contain_text("UNCERTAIN")
    expect(page.locator("#investigate-freeze")).to_be_disabled()


def test_complete_evidence_human_authorized_stable_receipt(page):
    base = os.environ["BROWSER_TEST_BASE_URL"]
    assert urlsplit(base).hostname in ("127.0.0.1", "localhost")
    accepted = page.request.post(base + "/api/showcase/complete", headers={"Idempotency-Key": str(uuid.uuid4())})
    assert accepted.ok, accepted.text()
    case_id = accepted.json()["caseId"]
    until = time.monotonic() + 60
    while time.monotonic() < until:
        row = page.request.get(base + "/api/v1/cases/" + case_id).json()
        if row["status"] == "PENDING_APPROVAL":
            break
        assert row["status"] in ("OPEN", "RUNNING"), row
        time.sleep(0.1)
    assert row["status"] == "PENDING_APPROVAL", row
    frozen = page.request.post(base + f"/api/v1/cases/{case_id}/investigation/passport")
    assert frozen.ok, frozen.text()
    approvals = page.request.get(base + "/api/approvals").json()
    run_id = page.request.get(base + f"/api/v1/cases/{case_id}/investigation").json()["run"]["run_id"]
    approval = next(a for a in approvals if any(
        f"run={run_id}" in finding["citations"]
        for finding in a["evidencePacket"]["agentFindings"]
    ))
    decision = page.request.post(base + f"/api/v1/approvals/{approval['id']}/decisions",
                                 headers={"If-Match": f'"{approval["version"]}"'},
                                 data={"decision": "APPROVE", "reasonCode": "SYNTHETIC_REVIEW", "comment": "Scripted test human authorization"})
    assert decision.ok, decision.text()
    receipt = decision.json()["executionLog"]
    assert any("receipt=sim-" in line for line in receipt), decision.json()
    reconciled = page.request.post(base + f"/api/approvals/{approval['id']}/reconcile")
    assert reconciled.ok, reconciled.text()
    assert reconciled.json()["executionLog"] == receipt
    page.goto(base + f"/mission-control.html?showcase=1&case={case_id}")
    expect(page.locator("#investigate-freeze")).to_be_enabled(timeout=30000)
    page.locator("#investigate-freeze").click()
    page.get_by_role("button", name="Refresh reviewer decision / receipt").click()
    expect(page.locator("#investigate-review-outcome")).to_contain_text("SIMULATED_RECEIPT_CONFIRMED")
    expect(page.locator("#investigate-review-outcome")).to_contain_text("no funds moved")
