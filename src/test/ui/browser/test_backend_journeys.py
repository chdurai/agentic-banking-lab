"""Real APIs and application workers; executed by BrowserJourneyIT, never by the fixture suite."""
import os
import re
import uuid
from urllib.parse import urlsplit

import pytest
from playwright.sync_api import expect

pytestmark = pytest.mark.skipif(
    not os.environ.get("BROWSER_TEST_BASE_URL"),
    reason="Run with mvn -Dtest=BrowserJourneyIT test to start an isolated backend",
)


@pytest.fixture
def application(page):
    base = os.environ.get("BROWSER_TEST_BASE_URL")
    assert urlsplit(base).hostname in ("127.0.0.1", "localhost"), "Tests must not submit to remote systems"
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(base)
    expect(page.get_by_text("API connected", exact=True)).to_be_visible()
    yield page
    hold_seconds = int(os.environ.get("BROWSER_DEMO_HOLD_SECONDS", "0"))
    if hold_seconds:
        page.wait_for_timeout(hold_seconds * 1000)
    assert not errors, f"Uncaught browser errors: {errors}"


def finish_loan(page):
    page.get_by_role("button", name="Continue to loan details", exact=False).click()
    page.get_by_label("Requested amount", exact=False).fill("15000")
    page.get_by_label("Loan purpose", exact=False).fill("Synthetic browser home repairs")
    page.get_by_label("Term in months", exact=False).fill("60")
    page.get_by_role("button", name="Review application", exact=False).click()
    page.get_by_label("I reviewed this demo applicant", exact=False).check()
    with page.expect_response(lambda r: "/applicants/" in r.url and r.url.endswith("/cases")
                              and r.request.method == "POST") as accepted:
        page.get_by_role("button", name="Confirm and submit", exact=False).click()
    assert accepted.value.status == 202
    case_id = accepted.value.json()["caseId"]
    expect(page.get_by_role("heading", name="Case workspace")).to_be_visible()
    expect(page.locator(".result-json")).to_contain_text("decision", timeout=60000)
    record = page.request.get(page.url.split("#")[0] + f"api/v1/cases/{case_id}")
    assert record.ok
    body = record.json()
    assert body["status"] in ("COMPLETED", "PENDING_APPROVAL")
    assert body["result"]["decision"] in ("APPROVED", "MANUAL_REVIEW")
    page.reload()
    expect(page.locator(".detail-id")).to_contain_text(case_id)
    return case_id


def test_seeded_applicant_real_loan(application):
    page = application
    page.locator('[data-start-flow="loan"]').click()
    page.get_by_label("Find by name or customer reference").fill("A001")
    page.get_by_role("button", name="Search", exact=True).click()
    page.locator('[data-applicant="A001"]').click()
    expect(page.locator("#case-dialog").get_by_role("heading", name="Jane Cooper")).to_be_visible()
    finish_loan(page)


def test_create_and_reuse_applicant(application):
    page = application
    name = f"Synthetic Browser Applicant {uuid.uuid4().hex[:10]}"
    page.locator('[data-start-flow="loan"]').click()
    page.get_by_role("button", name="New applicant?", exact=False).click()
    for label, value in [
        ("Full name", name), ("Synthetic SSN last four", "1111"),
        ("Employer", "Synthetic Browser Employer"), ("Annual income", "145000"),
        ("Years employed", "8"), ("Existing monthly debt", "1200"),
    ]:
        page.get_by_label(label, exact=False).fill(value)
    with page.expect_response(lambda r: r.url.endswith("/api/loans/applicants")
                              and r.request.method == "POST") as registered:
        page.get_by_role("button", name="Create applicant", exact=True).click()
    assert registered.value.status == 201
    profile = registered.value.json()
    assert profile["fullName"] == name
    assert "ssnLast4" not in profile
    assert profile["verificationStatus"] == "DECLARED_NOT_VERIFIED"
    expect(page.locator("#case-dialog").get_by_role("heading", name=name)).to_be_visible()
    page.get_by_role("button", name="Change applicant", exact=False).click()
    page.get_by_label("Find by name or customer reference").fill(name)
    page.get_by_role("button", name="Search", exact=True).click()
    page.get_by_role("button", name=name, exact=False).click()
    finish_loan(page)


@pytest.mark.parametrize("flow,final_decision", [
    ("incident", "APPROVE"), ("incident", "REJECT"),
    ("grievance", "APPROVE"), ("document", "APPROVE"),
])
def test_real_sample_submission(application, flow, final_decision):
    page = application
    page.locator(f'[data-start-flow="{flow}"]').click()
    page.get_by_role("button", name="Use a demo example").click()
    reference = {"incident": "incidentId", "grievance": "grievanceId", "document": "documentId"}[flow]
    expect(page.locator(f"#{reference}")).to_have_value(re.compile(r"DEMO-.+"))
    with page.expect_response(lambda r: r.url.endswith(f"/api/v1/{flow}/cases")
                              and r.request.method == "POST") as accepted:
        page.get_by_role("button", name="Submit case", exact=False).click()
    assert accepted.value.status == 202
    case_id = accepted.value.json()["caseId"]
    expect(page.locator(".result-json")).to_contain_text("status", timeout=60000)
    response = page.request.get(page.url.split("#")[0] + f"api/v1/cases/{case_id}")
    assert response.ok
    record = response.json()
    assert record["status"] in ("COMPLETED", "PENDING_APPROVAL")
    assert record["result"]
    if flow == "incident":
        assert record["status"] == "PENDING_APPROVAL"
        approval_id = record["result"]["approvalId"]
        with page.expect_response(lambda r: "/api/approvals?status=PENDING" in r.url):
            page.get_by_role("button", name="Refresh workspace", exact=True).click()
        page.get_by_role("button", name="Go to review queue", exact=False).click()
        page.locator(f'[data-approval-id="{approval_id}"]').click()
        page.get_by_role("button", name="Record independent assessment", exact=False).click()
        page.get_by_label("Reason code", exact=False).fill("BROWSER_INDEPENDENT")
        page.get_by_role("button", name=final_decision.title(), exact=True).click()
        page.get_by_role("button", name="Record a decision", exact=False).click()
        page.get_by_label("Reason code", exact=False).fill("BROWSER_REVIEWED")
        with page.expect_response(lambda r: r.url.endswith(f"/{approval_id}/decisions")) as decision:
            page.get_by_role("button", name=final_decision.title(), exact=True).click()
        assert decision.value.status == 200
        expected = "APPROVED" if final_decision == "APPROVE" else "REJECTED"
        assert decision.value.json()["status"] == expected
        page.goto(page.url.split("#")[0] + f"#case/{case_id}")
        expect(page.locator(".detail-header .status-pill")).to_have_text(expected.title(), timeout=15000)


def test_loan_validation(application):
    page = application
    submitted = []
    page.on("request", lambda request: submitted.append(request.url)
            if request.method == "POST" and request.url.endswith("/cases") else None)
    page.locator('[data-start-flow="loan"]').click()
    page.locator('[data-applicant="A001"]').click()
    page.get_by_role("button", name="Continue to loan details", exact=False).click()
    page.get_by_label("Requested amount", exact=False).fill("500")
    page.get_by_label("Loan purpose", exact=False).fill("Synthetic validation demonstration")
    page.get_by_role("button", name="Review application", exact=False).click()
    expect(page.get_by_label("Requested amount", exact=False)).to_be_visible()
    assert not page.locator("#requestedAmount").evaluate("(input) => input.checkValidity()")
    assert not submitted, "Invalid input must not create a case"


def test_applicant_not_found(application):
    page = application
    page.locator('[data-start-flow="loan"]').click()
    page.get_by_label("Find by name or customer reference").fill("NO-SUCH-SYNTHETIC-CUSTOMER")
    page.get_by_role("button", name="Search", exact=True).click()
    expect(page.get_by_text("No matching applicants.", exact=False)).to_be_visible()
    expect(page.get_by_role("button", name="New applicant?", exact=False)).to_be_visible()
