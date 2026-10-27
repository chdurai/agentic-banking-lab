import json
from urllib.parse import urlsplit

import pytest
from playwright.sync_api import expect


@pytest.fixture
def react_workspace(page, base_url):
    case = {
        "caseId": "react-case-1",
        "flow": "loan",
        "status": "COMPLETED",
        "version": 3,
        "createdAt": "2026-05-01T10:00:00Z",
        "updatedAt": "2026-05-01T10:05:00Z",
        "result": {
            "title": "Synthetic loan review",
            "decision": "APPROVED",
            "summary": "Synthetic fixture outcome",
        },
    }
    approval = {
        "id": "review-1",
        "flow": "incident",
        "title": "Review synthetic incident action",
        "status": "PENDING",
        "createdAt": "2026-05-01T10:00:00Z",
        "expiresAt": "2026-05-02T10:00:00Z",
        "requiredRole": "INCIDENT_REVIEWER",
        "details": ["Synthetic demo action"],
    }
    state = {"case_status": 200}
    errors = []
    unexpected = []
    page.on("pageerror", lambda error: errors.append(str(error)))

    def api(route):
        path = urlsplit(route.request.url).path
        if path == "/api/v1/cases":
            status = state["case_status"]
            body = {"detail": "Synthetic API unavailable"} if status != 200 else {
                "items": [case],
                "nextCursor": None,
            }
        elif path == "/api/approvals":
            status, body = 200, [approval]
        elif path == "/api/v1/cases/react-case-1":
            status, body = 200, case
        else:
            status, body = 404, {"detail": "Unexpected API request"}
            unexpected.append(f"{route.request.method} {path}")
        route.fulfill(status=status, content_type="application/json", body=json.dumps(body))

    page.route("**/api/**", api)
    yield {"page": page, "url": base_url, "case": case, "approval": approval, "state": state}
    assert not errors, f"Uncaught browser errors: {errors}"
    assert not unexpected, f"Unexpected API requests: {unexpected}"


def test_react_case_list_and_detail_use_spring_api(react_workspace):
    page = react_workspace["page"]
    page.goto(react_workspace["url"] + "/react-workbench/")

    expect(page.get_by_role("heading", name="Operations overview")).to_be_visible()
    expect(page.get_by_role("button", name="Synthetic loan review")).to_be_visible()
    page.get_by_role("button", name="Synthetic loan review").click()
    expect(page.get_by_role("heading", name="Case details")).to_be_visible()
    expect(page.get_by_text("Version 3", exact=True)).to_be_visible()
    expect(page.locator(".case-detail pre")).to_contain_text("Synthetic fixture outcome")


def test_react_review_queue_renders_pending_approval(react_workspace):
    page = react_workspace["page"]
    page.goto(react_workspace["url"] + "/react-workbench/?view=reviews")

    expect(page.get_by_role("heading", name="Human review queue")).to_be_visible()
    expect(page.get_by_text("Review synthetic incident action", exact=True)).to_be_visible()
    expect(page.get_by_text("Pending review", exact=True)).to_be_visible()


def test_react_api_failure_is_visible_without_sample_cases(react_workspace):
    react_workspace["state"]["case_status"] = 503
    page = react_workspace["page"]
    page.goto(react_workspace["url"] + "/react-workbench/?view=cases")

    expect(page.get_by_role("alert")).to_contain_text("Synthetic API unavailable")
    expect(page.get_by_text("Case results are unavailable because the API request failed.")).to_be_visible()
    expect(page.get_by_text("Synthetic loan review", exact=True)).to_have_count(0)
