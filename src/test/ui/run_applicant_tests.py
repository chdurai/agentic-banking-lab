"""Run isolated UI behavior tests: pip install quickjs; python3 src/test/ui/run_applicant_tests.py."""
from pathlib import Path
import sys

try:
    import quickjs
except ImportError:
    sys.exit("Missing test runtime: install quickjs into your test environment first.")

root = Path(__file__).resolve().parents[3]
context = quickjs.Context()
context.eval((root / "src/test/ui/applicant-flow.test.js").read_text())
context.eval((root / "src/main/resources/static/app.js").read_text())
context.eval("""
globalThis.testDone = false;
globalThis.testFailure = "";
runApplicantTests().then(() => testDone = true).catch(error => {
    testFailure = error.stack || String(error);
    testDone = true;
});
""")
while context.execute_pending_job():
    pass
if not context.eval("testDone"):
    sys.exit("UI tests did not finish.")
if context.eval("testFailure"):
    sys.exit(context.eval("testFailure"))
print(context.eval("JSON.stringify(testResults, null, 2)"))
print(f"PASS: {context.eval('testResults.length')} applicant UI behavior assertions")
