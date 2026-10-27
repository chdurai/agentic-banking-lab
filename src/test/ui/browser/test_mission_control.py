"""Mission Control live-observability journeys; executed by MissionControlJourneyIT.

These are pacing-first demo stories: run them headed with --slow-ms (via demo.py) and watch the
constellation, live feed, LLM inspector and agent-to-agent lane react to real workflow traffic.
The application under test runs the DAG runtime with the challenge protocol v2 enabled.
"""
import os
import re
from urllib.parse import urlsplit

import pytest
from playwright.sync_api import expect

pytestmark = pytest.mark.skipif(
    not os.environ.get("BROWSER_TEST_BASE_URL"),
    reason="Run with mvn -Dtest=MissionControlJourneyIT test (or demo.py) to start an isolated backend",
)

PIPELINE_TIMEOUT = 120_000  # ms; the stub model is fast, but leave generous room for slow machines


@pytest.fixture
def mission_control(page):
    """Open Mission Control, wait for the live SSE connection, collect page errors, hold at the end."""
    base = os.environ.get("BROWSER_TEST_BASE_URL")
    assert urlsplit(base).hostname in ("127.0.0.1", "localhost"), "Tests must not submit to remote systems"
    errors = []
    page.set_viewport_size({"width": 1560, "height": 980})
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(base + "/mission-control.html")
    expect(page.locator("#connection-label")).to_have_text("live", timeout=15000)
    yield {"page": page, "base": base, "errors": errors}
    hold_seconds = int(os.environ.get("BROWSER_DEMO_HOLD_SECONDS", "0"))
    if hold_seconds:
        page.wait_for_timeout(hold_seconds * 1000)
    assert not errors, f"Uncaught browser errors: {errors}"


def workbench_loan(page, base, errors):
    """Drive the workbench loan wizard for the seeded A001 applicant (visible, slow-paced)."""
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(base)
    expect(page.get_by_text("API connected", exact=True)).to_be_visible()
    page.locator('[data-start-flow="loan"]').click()
    page.get_by_label("Find by name or customer reference").fill("A001")
    page.get_by_role("button", name="Search", exact=True).click()
    page.locator('[data-applicant="A001"]').click()
    expect(page.locator("#case-dialog").get_by_role("heading", name="Jane Cooper")).to_be_visible()
    page.get_by_role("button", name="Continue to loan details", exact=False).click()
    page.get_by_label("Requested amount", exact=False).fill("15000")
    page.get_by_label("Loan purpose", exact=False).fill("Synthetic browser home repairs")
    page.get_by_label("Term in months", exact=False).fill("60")
    page.get_by_role("button", name="Review application", exact=False).click()
    page.get_by_label("I reviewed this demo applicant", exact=False).check()
    with page.expect_response(lambda r: urlsplit(r.url).path == "/api/loans/applicants/A001/cases"
                              and r.request.method == "POST") as accepted:
        page.get_by_role("button", name="Confirm and submit", exact=False).click()
    assert accepted.value.status == 202
    return accepted.value.json()["caseId"]


def expect_swarm_activity(mc, findings=True):
    """Assertions every Mission Control scenario shares once a flow has run."""
    # Agent runs appear as RUNNING→outcome cards in the live feed.
    expect(mc.locator("#feed .ev-kind-AGENT_COMPLETED").first).to_be_visible(timeout=PIPELINE_TIMEOUT)
    # Every agent call goes through the LLM gateway: the inspector shows prompt + response.
    expect(mc.locator("#llm-feed .llm").first).to_be_visible(timeout=PIPELINE_TIMEOUT)
    expect(mc.locator("#stat-llm")).not_to_have_text("0", timeout=PIPELINE_TIMEOUT)
    expect(mc.locator("#stat-rate")).not_to_have_text("0")
    if findings:
        # The loan flow keeps a typed blackboard: each agent report lands as a FINDING event.
        expect(mc.locator("#feed .ev-kind-FINDING").first).to_be_visible(timeout=PIPELINE_TIMEOUT)


def test_crowded_llm_inspector_keeps_cards_readable(mission_control):
    """A full inspector scrolls rather than shrinking cards and clipping expanded content."""
    page = mission_control["page"]
    page.evaluate("""() => {
        document.querySelector("#llm-feed").replaceChildren();
        for (let i = 0; i < 50; i++) {
            const card = document.createElement("details");
            card.className = "llm";
            card.innerHTML = `<summary><span class="llm-icon">L</span>
                <span class="llm-head"><span class="llm-title">Agent ${i}</span>
                <span class="llm-sub">Actual provider exchange</span></span>
                <span class="llm-side">1234ms</span></summary>
                <div class="llm-body"><pre>Provider response ${i}</pre></div>`;
            document.querySelector("#llm-feed").append(card);
        }
    }""")
    card = page.locator("#llm-feed .llm").first
    card.locator("summary").click()
    expect(card.locator("pre")).to_be_visible()
    sizes = page.evaluate("""() => {
        const feed = document.querySelector("#llm-feed");
        const cards = [...feed.querySelectorAll(".llm")];
        return {
            scrolls: feed.scrollHeight > feed.clientHeight,
            unclipped: cards.every(card => card.clientHeight >=
                card.querySelector("summary").offsetHeight),
            expandedFits: cards[0].clientHeight >=
                cards[0].querySelector("summary").offsetHeight +
                cards[0].querySelector(".llm-body").offsetHeight,
            noOverlap: cards.slice(1).every((card, i) =>
                card.getBoundingClientRect().top >= cards[i].getBoundingClientRect().bottom)
        };
    }""")
    assert all(sizes.values()), sizes
    page.locator("#llm-feed .llm").last.scroll_into_view_if_needed()
    expect(page.locator("#llm-feed .llm").last.locator("summary")).to_be_in_viewport()


def test_demo_launch_lights_the_constellation(mission_control):
    """One click on 'Launch demo scenario' runs a synthetic loan through the full pipeline."""
    mc = mission_control["page"]
    mc.locator("#launch-demo").click()
    expect(mc.locator("#toast")).to_contain_text("Demo scenario launched", timeout=15000)
    expect_swarm_activity(mc)
    # The LLM inspector expands to reveal the actual prompt and structured response.
    mc.locator("#llm-feed .llm summary").first.click()
    expect(mc.locator("#llm-feed .llm[open] .llm-body pre").first).to_be_visible()
    expect(mc.locator("#llm-feed .llm[open]")).to_contain_text("prompt", timeout=15000)
    # The feed can be paused and resumed without losing events.
    mc.locator("#pause-feed").click()
    mc.locator("#pause-feed").click()
    expect(mc.locator("#toast")).to_contain_text("Scenario complete", timeout=PIPELINE_TIMEOUT)


def test_workbench_loan_watched_from_mission_control(mission_control, context):
    """A loan submitted from the real workbench UI lights up Mission Control in a second tab."""
    mc = mission_control["page"]
    workbench = context.new_page()
    case_id = workbench_loan(workbench, mission_control["base"], mission_control["errors"])
    mc.bring_to_front()
    expect_swarm_activity(mc)
    # The final decision lands as a platform lifecycle event in the same stream.
    expect(mc.locator("#feed .ev-kind-FLOW").first).to_be_visible(timeout=PIPELINE_TIMEOUT)
    # The canvas caption narrates the latest activity; the case id is part of the story.
    expect(mc.locator("#canvas-caption")).not_to_have_text(
        "Waiting for the first flow — launch a demo scenario or submit a case from the workbench.",
        timeout=PIPELINE_TIMEOUT)
    record = mc.request.get(mission_control["base"] + f"/api/v1/cases/{case_id}")
    assert record.ok
    assert record.json()["status"] in ("COMPLETED", "PENDING_APPROVAL")


def test_multi_flow_swarm(mission_control, context):
    """Incident + grievance + document demo examples fire every flow cluster at once."""
    mc = mission_control["page"]
    workbench = context.new_page()
    workbench.on("pageerror", lambda error: mission_control["errors"].append(str(error)))
    workbench.goto(mission_control["base"])
    expect(workbench.get_by_text("API connected", exact=True)).to_be_visible()
    for flow, reference in [("incident", "incidentId"), ("grievance", "grievanceId"),
                            ("document", "documentId")]:
        workbench.locator(f'[data-start-flow="{flow}"]').click()
        workbench.get_by_role("button", name="Use a demo example").click()
        expect(workbench.locator(f"#{reference}")).to_have_value(re.compile(r"DEMO-.+"))
        expected_path = f"/api/v1/{flow}/cases"
        with workbench.expect_response(lambda r, path=expected_path: urlsplit(r.url).path == path
                                       and r.request.method == "POST") as accepted:
            workbench.get_by_role("button", name="Submit case", exact=False).click()
        assert accepted.value.status == 202
        workbench.goto(mission_control["base"])
        expect(workbench.get_by_text("API connected", exact=True)).to_be_visible()
    mc.bring_to_front()
    expect_swarm_activity(mc, findings=False)  # incident/grievance/document don't publish typed findings
    # Flow chips make the mix visible: incident, grievance and document events in one stream.
    for flow in ("incident", "grievance", "document"):
        expect(mc.locator(f"#feed .ev-flow-{flow}").first).to_be_visible(timeout=PIPELINE_TIMEOUT)
    expect(mc.locator("#stat-cases")).not_to_have_text("0", timeout=PIPELINE_TIMEOUT)
