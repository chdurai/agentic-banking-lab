const {test} = require("node:test");
const {readFileSync} = require("node:fs");
const {resolve} = require("node:path");
const vm = require("node:vm");

for (const [fixture, implementation, entrypoint] of [
  ["applicant-flow.test.js", "app.js", "runApplicantTests"],
  ["investigation-flow.test.js", "investigation.js", "runInvestigationTests"]
]) {
  test(`${entrypoint} (isolated DOM contract, not browser rendering)`, async () => {
    const context = vm.createContext({console});
    vm.runInContext(readFileSync(resolve(__dirname, fixture), "utf8"), context);
    vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/static", implementation), "utf8"), context);
    await vm.runInContext(`${entrypoint}()`, context);
  });
}
