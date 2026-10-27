import json
import os
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

import pytest


@pytest.fixture(scope="session")
def browser_type_launch_args(browser_type_launch_args):
    # Crashpad's shared ~/Library/Application Support store is unavailable in some sandboxes/CI
    # runners; crash reporting is never needed for tests and its failure is fatal there.
    args = list(browser_type_launch_args.get("args", []))
    args += ["--disable-crashpad", "--disable-crash-reporter"]
    launch = {
        **browser_type_launch_args,
        "slow_mo": int(os.environ.get("BROWSER_DEMO_SLOW_MS", "0")),
        "args": args,
    }
    # BROWSER_FAKE_HOME relocates Chromium's per-user dirs (Crashpad et al.) for sandboxed runs.
    fake_home = os.environ.get("BROWSER_FAKE_HOME")
    if fake_home:
        launch["env"] = {**os.environ, "HOME": fake_home}
    return launch


@pytest.fixture(scope="session")
def base_url():
    static = Path(__file__).resolve().parents[3] / "main/resources/static"

    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(Handler, directory=str(static)))
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_port}"
    server.shutdown()
    server.server_close()
    thread.join()


@pytest.fixture
def workspace(page, base_url, request):
    page.set_viewport_size(
        {"width": 390, "height": 844}
        if request.config.getoption("--mobile-layout")
        else {"width": 1440, "height": 1000}
    )
    errors = []
    unexpected = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    profile = {
        "applicantId": "A001", "fullName": "Jane Cooper", "annualIncome": 145000,
        "employmentYears": 8, "employer": "Acme Corp", "existingMonthlyDebt": 1200,
        "documents": ["paystubs.pdf"], "source": "Synthetic demo customer master",
        "verificationStatus": "SIMULATED_RECORD", "recordedAt": "2026-01-01T00:00:00Z",
        "version": 1,
    }
    case = {
        "caseId": "browser-case-1", "flow": "loan", "status": "COMPLETED", "version": 2,
        "createdAt": "2026-01-01T00:00:00Z", "updatedAt": "2026-01-01T00:00:01Z",
        "result": {"decision": "APPROVED", "reasoning": "Synthetic browser fixture outcome",
                   "approvedAmount": 15000, "interestRateApr": 6.5},
    }
    submitted = []

    def api(route):
        path = route.request.url.split("/api/", 1)[1].split("?", 1)[0]
        method = route.request.method
        status = 200
        if path == "v1/cases":
            body = {"items": [case] if submitted else [], "nextCursor": None}
        elif path == "approvals":
            body = []
        elif path == "loans/applicants":
            body = [profile]
        elif path == "loans/applicants/A001":
            body = profile
        elif path == "loans/applicants/A001/cases" and method == "POST":
            submitted.append({
                "body": route.request.post_data_json,
                "key": route.request.headers.get("idempotency-key"),
            })
            body = case
            status = 202
        elif path == "v1/cases/browser-case-1":
            body = case
        else:
            unexpected.append(f"{method} /api/{path}")
            status, body = 501, {"detail": "Unexpected browser test request"}
        route.fulfill(status=status, content_type="application/json", body=json.dumps(body))

    page.route("**/api/**", api)
    yield {"page": page, "url": base_url, "submitted": submitted, "case": case}
    assert not errors, f"Uncaught browser errors: {errors}"
    assert not unexpected, f"Unexpected API requests: {unexpected}"


def pytest_addoption(parser):
    parser.addoption("--mobile-layout", action="store_true", default=False)
