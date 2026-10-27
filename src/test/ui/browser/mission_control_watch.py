"""Watch Mission Control live against an already-running local application (real model, real latency).

No Maven, no test application: point this at your normal `mvn spring-boot:run` instance and it opens a
visible Chromium window, then slowly drives real demo traffic while you watch the agent constellation,
the live request/response stream, the LLM inspector and the agent-to-agent lane.

    .venv-browser/bin/python src/test/ui/browser/mission_control_watch.py
    .venv-browser/bin/python src/test/ui/browser/mission_control_watch.py --slow-ms 900 --loop
    .venv-browser/bin/python src/test/ui/browser/mission_control_watch.py --base-url http://localhost:8080 --hold-seconds 30

Synthetic demo data only. Stop with Ctrl+C.
"""
import argparse
import sys

from playwright.sync_api import sync_playwright


def narrate(text):
    print(f"\n\033[96m▶ {text}\033[0m", flush=True)


def open_mission_control(context, base):
    page = context.new_page()
    page.goto(base + "/mission-control.html")
    try:
        page.wait_for_function(
            "document.querySelector('#connection-label').textContent === 'live'", timeout=15000)
    except Exception:
        browser_hint = "is the app running with caller auth disabled (dev mode)?"
        raise RuntimeError(f"Mission Control never connected to the live event stream — {browser_hint}")
    return page


def card_count(page, selector):
    return page.locator(selector).count()


def scenario_demo_launch(mc):
    narrate("Scenario 1 — 'Launch demo scenario': a synthetic loan runs the full agent pipeline. "
            "Watch nodes pulse, then open an LLM inspector card to read the real prompt and response.")
    llm_cards_before = card_count(mc, "#llm-feed .llm")
    mc.locator("#launch-demo").click()
    mc.wait_for_function(
        f"document.querySelectorAll('#llm-feed .llm').length > {llm_cards_before}", timeout=180_000)
    mc.wait_for_timeout(1500)
    # Expand the newest LLM exchange so the prompt/response is on screen.
    mc.locator("#llm-feed .llm summary").first.click()


def scenario_workbench_loan(context, base, mc):
    narrate("Scenario 2 — a loan submitted from the actual workbench, watched live from Mission Control.")
    wb = context.new_page()
    try:
        wb.goto(base)
        wb.locator('[data-start-flow="loan"]').click()
        wb.get_by_label("Find by name or customer reference").fill("A001")
        wb.get_by_role("button", name="Search", exact=True).click()
        # Wait for the search to settle before concluding anything about A001.
        try:
            wb.locator('[data-applicant="A001"]').wait_for(state="visible", timeout=10000)
        except Exception:
            print("   (seeded applicant A001 not found in this environment — skipping workbench loan)")
            return
        wb.locator('[data-applicant="A001"]').click()
        wb.get_by_role("button", name="Continue to loan details", exact=False).click()
        wb.get_by_label("Requested amount", exact=False).fill("15000")
        wb.get_by_label("Loan purpose", exact=False).fill("Synthetic watch demo")
        wb.get_by_label("Term in months", exact=False).fill("60")
        wb.get_by_role("button", name="Review application", exact=False).click()
        wb.get_by_label("I reviewed this demo applicant", exact=False).check()
        flow_cards_before = card_count(mc, "#feed .ev-kind-FLOW")
        with wb.expect_response(
                lambda r: r.url.endswith("/api/loans/applicants/A001/cases")
                and r.request.method == "POST", timeout=60000) as accepted:
            wb.get_by_role("button", name="Confirm and submit", exact=False).click()
        if accepted.value.status != 202:
            print(f"   (loan submission returned HTTP {accepted.value.status} — skipping)")
            return
        narrate("   Loan accepted — back to Mission Control to watch the swarm finish it.")
        mc.bring_to_front()
        # Wait for a NEW lifecycle event, not one retained from an earlier loop iteration.
        mc.wait_for_function(
            f"document.querySelectorAll('#feed .ev-kind-FLOW').length > {flow_cards_before}",
            timeout=180_000)
    finally:
        wb.close()


def scenario_multi_flow(context, base, mc):
    narrate("Scenario 3 — incident, grievance and document demo examples back to back: "
            "watch the flow-colored chips mix in one live stream.")
    wb = context.new_page()
    # Baseline counts so a repeated loop waits for NEW cards, not retained ones.
    baseline = {f: card_count(mc, f"#feed .ev-flow-{f}") for f in ("incident", "grievance", "document")}
    try:
        wb.goto(base)
        for flow in ("incident", "grievance", "document"):
            wb.locator(f'[data-start-flow="{flow}"]').click()
            wb.get_by_role("button", name="Use a demo example").click()
            with wb.expect_response(
                    lambda r, f=flow: r.url.endswith(f"/api/v1/{f}/cases")
                    and r.request.method == "POST", timeout=60000) as accepted:
                wb.get_by_role("button", name="Submit case", exact=False).click()
            if accepted.value.status != 202:
                print(f"   ({flow} submission returned HTTP {accepted.value.status} — continuing)")
            wb.goto(base)
        mc.bring_to_front()
        for flow, before in baseline.items():
            mc.wait_for_function(
                f"document.querySelectorAll('#feed .ev-flow-{flow}').length > {before}",
                timeout=180_000)
    finally:
        wb.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="A locally running application (loopback only)")
    parser.add_argument("--slow-ms", type=int, default=600, help="Delay between browser actions (0–2000)")
    parser.add_argument("--hold-seconds", type=int, default=20,
                        help="Dwell time on Mission Control after each scenario (0–120)")
    parser.add_argument("--loop", action="store_true", help="Repeat the story until Ctrl+C")
    parser.add_argument("--headless", action="store_true", help="Run without a visible window")
    args = parser.parse_args()
    if not args.base_url.startswith(("http://localhost", "http://127.0.0.1")):
        parser.error("Refusing to drive anything but a loopback application.")
    if not 0 <= args.slow_ms <= 2000 or not 0 <= args.hold_seconds <= 120:
        parser.error("--slow-ms must be 0–2000 and --hold-seconds must be 0–120.")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=args.headless, slow_mo=args.slow_ms)
        context = browser.new_context(viewport={"width": 1560, "height": 980})
        try:
            narrate(f"Opening Mission Control at {args.base_url} …")
            mc = open_mission_control(context, args.base_url)
            while True:
                for scenario in (scenario_demo_launch, scenario_workbench_loan, scenario_multi_flow):
                    try:
                        if scenario is scenario_demo_launch:
                            scenario(mc)
                        else:
                            scenario(context, args.base_url, mc)
                    except Exception as e:
                        print(f"   (scenario skipped: {e})")
                    narrate(f"Holding the console for {args.hold_seconds}s — explore the panels.")
                    mc.wait_for_timeout(args.hold_seconds * 1000)
                if not args.loop:
                    break
                narrate("Looping the story from the top…")
        except KeyboardInterrupt:
            print("\nStopped by user.")
        finally:
            context.close()
            browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
