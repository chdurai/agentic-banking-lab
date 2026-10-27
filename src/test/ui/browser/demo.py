"""Launch a named, visible browser demo against an isolated test application."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


SCENARIOS = {
    "existing-loan": "Find Jane, review stored data, submit a loan, and reopen the outcome.",
    "new-applicant": "Create a synthetic customer, find the saved record, and submit a loan.",
    "incident-approve": "Submit an incident, independently assess it, and approve simulated remediation.",
    "incident-reject": "Submit an incident and reject the proposed remediation after independent review.",
    "grievance": "Load a synthetic grievance, route it, and inspect the unsent response draft.",
    "document": "Load synthetic document text and inspect classification and extracted fields.",
    "loan-validation": "Demonstrate that an amount below the minimum cannot create a case.",
    "applicant-not-found": "Search for an unknown customer and show the onboarding option.",
    "mission-control": "Open the Mission Control console and launch a synthetic loan; watch every agent, "
                       "LLM prompt/response and agent-to-agent message live.",
    "mc-swarm": "Submit a real loan from the workbench while Mission Control visualizes the agent swarm "
                "in a second tab.",
    "mc-multi-flow": "Fire incident, grievance and document scenarios and watch every flow cluster light "
                     "up in Mission Control at once.",
    "all": "Run every backend scenario in sequence, with a fresh browser context for each.",
}

# Mission Control stories run against MissionControlJourneyIT (DAG runtime + challenge protocol v2).
MISSION_CONTROL_SCENARIOS = {"mission-control", "mc-swarm", "mc-multi-flow"}


def find_maven(explicit, env):
    if explicit:
        return shutil.which(explicit)
    found = shutil.which("mvn")
    if found:
        return found
    candidates = [
        Path(env[key]) / "bin/mvn"
        for key in ("MAVEN_HOME", "M2_HOME") if env.get(key)
    ]
    if sys.platform == "darwin":
        for applications in (Path("/Applications"), Path.home() / "Applications"):
            candidates.append(applications / (
                "IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"))
            candidates.append(applications / (
                "IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn"))
            candidates.extend(sorted(applications.glob(
                "IntelliJ IDEA*.app/Contents/plugins/maven/lib/maven3/bin/mvn")))
    return next((str(path) for path in candidates if path.is_file()
                 and os.access(path, os.X_OK)), None)


def java_major(home):
    release = home / "release"
    if not release.is_file() or not (home / "bin/java").is_file():
        return None
    for line in release.read_text().splitlines():
        if line.startswith("JAVA_VERSION="):
            version = line.split("=", 1)[1].strip('"').split(".")[0]
            return int(version) if version.isdigit() else None
    return None


def find_java_home(explicit, env):
    if explicit or env.get("JAVA_HOME"):
        home = Path(explicit or env["JAVA_HOME"]).expanduser()
        return home if java_major(home) == 25 else None
    roots = [Path.home() / ".jdks", Path.home() / "Library/Java/JavaVirtualMachines",
             Path("/Library/Java/JavaVirtualMachines")]
    candidates = []
    for root in roots:
        candidates.extend(sorted(root.glob("*/Contents/Home")))
        candidates.extend(sorted(root.glob("*")))
    executable = shutil.which("java")
    if executable and executable != "/usr/bin/java":
        candidates.insert(0, Path(executable).resolve().parent.parent)
    if sys.platform == "darwin":
        candidates.append(Path("/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"))
        candidates.extend(sorted(Path("/Applications").glob(
            "IntelliJ IDEA*.app/Contents/jbr/Contents/Home")))
    return next((home for home in candidates if java_major(home) == 25), None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=SCENARIOS, nargs="?")
    parser.add_argument("--list", action="store_true", help="List available scenario stories")
    parser.add_argument("--slow-ms", type=int, default=450, help="Delay browser actions (0–2000 ms)")
    parser.add_argument("--hold-seconds", type=int, default=15, help="Keep each final screen visible (0–60)")
    parser.add_argument("--headless", action="store_true", help="Run without a visible browser")
    parser.add_argument("--maven", help="Maven executable path (otherwise discovered automatically)")
    parser.add_argument("--java-home", help="JDK 25 home directory (otherwise discovered automatically)")
    args = parser.parse_args()
    if args.list:
        for name, story in SCENARIOS.items():
            print(f"{name:22} {story}")
        return 0
    if not args.scenario:
        parser.error("Choose a scenario, or use --list.")
    if not 0 <= args.slow_ms <= 2000 or not 0 <= args.hold_seconds <= 60:
        parser.error("--slow-ms must be 0–2000 and --hold-seconds must be 0–60.")
    maven = find_maven(args.maven, os.environ)
    if not maven:
        parser.error("Maven was not found in PATH, MAVEN_HOME/M2_HOME, or IntelliJ. "
                     'Pass --maven "/path/to/maven/bin/mvn".')
    java_home = find_java_home(args.java_home, os.environ)
    if not java_home:
        parser.error("JDK 25 was not found, or the supplied JAVA_HOME is not JDK 25. "
                     'Pass --java-home "/path/to/jdk-25/Contents/Home". '
                     "Use the SDK path shown in IntelliJ Project Structure.")
    try:
        import playwright.sync_api
        import pytest
    except ImportError:
        parser.error("Install src/test/ui/browser/requirements.txt using this Python first.")
    root = Path(__file__).resolve().parents[4]
    env = {
        **os.environ, "BROWSER_TEST_PYTHON": sys.executable,
        "BROWSER_DEMO_SCENARIO": args.scenario,
        "BROWSER_DEMO_HEADED": str(not args.headless).lower(),
        "BROWSER_DEMO_SLOW_MS": str(args.slow_ms),
        "BROWSER_DEMO_HOLD_SECONDS": str(args.hold_seconds),
        "JAVA_HOME": str(java_home),
    }
    print(f"Scenario: {args.scenario}\n{SCENARIOS[args.scenario]}", flush=True)
    print(f"Maven: {maven}\nJDK: {java_home}", flush=True)
    print("Starting an isolated application. Only the AI model is stubbed; APIs and workers are real.",
          flush=True)
    journey = "MissionControlJourneyIT" if args.scenario in MISSION_CONTROL_SCENARIOS else "BrowserJourneyIT"
    return subprocess.run([maven, "-B", f"-Dtest={journey}", "test"], cwd=root, env=env).returncode


if __name__ == "__main__":
    sys.exit(main())
