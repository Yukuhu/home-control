import assert from "node:assert/strict";
import { mkdtemp, mkdir, copyFile, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { MARKER, buildModel, collectSuite, fetchGate, main, parseJUnit, render } from "./summary.mjs";

const fixtures = fileURLToPath(new URL("./fixtures/", import.meta.url));
const fixture = (name) => readFile(path.join(fixtures, name), "utf8");

// ---- parseJUnit

test("counts passed and skipped tests and ignores captured output", async () => {
    assert.deepEqual(parseJUnit(await fixture("passed.xml")), { passed: 2, failed: 0, skipped: 1, failures: [] });
});

test("counts failures and errors as failed and keeps their message and trace", async () => {
    const result = parseJUnit(await fixture("failed.xml"));
    assert.equal(result.passed, 1);
    assert.equal(result.failed, 3);
    assert.deepEqual(result.failures.map((failure) => failure.name),
        ["reportsInferredPlaybackAfterLaunch()", "reconnectsAfterATimeout()", "failsWithoutSayingWhy()"]);
    const [assertion, error] = result.failures;
    assert.equal(assertion.className, "dev.andre.homecontrol.adapters.androidtv.AndroidTvSessionTest");
    assert.equal(assertion.message, "org.opentest4j.AssertionFailedError: expected: <PLAYING> but was: <IDLE>");
    assert.match(assertion.trace, /^org\.opentest4j.*\n\tat org\.junit/);
    assert.equal(error.message, "java.lang.IllegalStateException: socket closed");
});

test("a failure without a message or a body still counts and says so", async () => {
    const failure = parseJUnit(await fixture("failed.xml")).failures[2];
    assert.equal(failure.message, "No failure message");
    assert.equal(failure.trace, "");
});

test("a failure with only a body uses its first line as the message", () => {
    const xml = '<testsuite><testcase name="a()" classname="x.Y"><failure>first line\nsecond line</failure></testcase></testsuite>';
    assert.equal(parseJUnit(xml).failures[0].message, "first line");
});

test("an empty suite has no tests", () => {
    assert.deepEqual(parseJUnit('<testsuite name="x" tests="0"/>'), { passed: 0, failed: 0, skipped: 0, failures: [] });
});

test("reads suites wrapped in a testsuites element", () => {
    const xml = '<testsuites><testsuite><testcase name="a()" classname="x.Y"/></testsuite>'
        + '<testsuite><testcase name="b()" classname="x.Z"/></testsuite></testsuites>';
    assert.equal(parseJUnit(xml).passed, 2);
});

test("decodes entities in parameterised test names", async () => {
    const xml = '<testsuite><testcase name="opens(String) [&quot;chromium&quot;]" classname="x.Y">'
        + "<failure message=\"m\"/></testcase></testsuite>";
    assert.equal(parseJUnit(xml).failures[0].name, 'opens(String) ["chromium"]');
});

test("decodes numeric references, which Gradle uses for line breaks in messages", () => {
    const xml = '<testsuite><testcase name="a()" classname="x.Y">'
        + '<failure message="first&#10;second&#x9;tabbed"/></testcase></testsuite>';
    assert.equal(parseJUnit(xml).failures[0].message, "first\nsecond\ttabbed");
});

test("reads a result file with thousands of entities and megabytes of captured output", () => {
    const cases = Array.from({ length: 3000 },
        (_, index) => `<testcase name="t${index}(String) [&quot;webkit&quot;] &lt;&amp;&gt;" classname="x.Y"/>`);
    const xml = `<testsuite>${cases.join("")}<system-out><![CDATA[${"log <line> & more\n".repeat(200000)}]]></system-out></testsuite>`;
    assert.equal(parseJUnit(xml).passed, 3000);
});

test("rejects malformed XML and documents that are not JUnit results", async () => {
    assert.throws(() => parseJUnit(""), /Malformed JUnit XML/);
    assert.throws(() => parseJUnit("<html></html>"), /Malformed JUnit XML/);
    await assert.rejects(async () => parseJUnit(await fixture("truncated.xml")), /Malformed JUnit XML/);
});

// ---- collectSuite

async function directory(t, ...names) {
    const root = await mkdtemp(path.join(tmpdir(), "home-control-summary-"));
    t.after(() => rm(root, { recursive: true, force: true }));
    for (const name of names) await copyFile(path.join(fixtures, name), path.join(root, `TEST-${name}`));
    return root;
}

test("sums every result file in a directory", async (t) => {
    const result = await collectSuite(await directory(t, "passed.xml", "failed.xml"));
    assert.deepEqual({ ...result, failures: result.failures.length },
        { found: true, unreadable: 0, passed: 3, failed: 3, skipped: 1, failures: 3 });
});

test("finds result files in nested directories and ignores other files", async (t) => {
    const root = await directory(t);
    await mkdir(path.join(root, "nested"));
    await copyFile(path.join(fixtures, "passed.xml"), path.join(root, "nested", "TEST-x.xml"));
    await copyFile(path.join(fixtures, "failed.xml"), path.join(root, "nested", "other.xml"));
    const result = await collectSuite(root);
    assert.equal(result.passed, 2);
    assert.equal(result.failed, 0);
});

test("an empty or missing directory has no results", async (t) => {
    const empty = { found: false, unreadable: 0, passed: 0, failed: 0, skipped: 0, failures: [] };
    const root = await directory(t);
    assert.deepEqual(await collectSuite(root), empty);
    assert.deepEqual(await collectSuite(path.join(root, "absent")), empty);
});

test("a truncated result file is counted as unreadable and the others still count", async (t) => {
    const result = await collectSuite(await directory(t, "passed.xml", "truncated.xml"));
    assert.equal(result.found, true);
    assert.equal(result.unreadable, 1);
    assert.equal(result.passed, 2);
});

// ---- fetchGate

const HEAD = "1277414c04bec9054494becf05affdd7db790d53";
const gateOptions = { projectKey: "Yukuhu_home-control", pullRequest: "105", headSha: HEAD, token: "secret" };

function sonar({ sha = HEAD, status = "OK", conditions = [], listStatus = 200, gateStatus = 200 } = {}) {
    const requests = [];
    const fetch = async (url, options) => {
        requests.push({ url, options });
        const list = url.includes("project_pull_requests/list");
        const body = list
            ? { pullRequests: [{ key: "104", commit: { sha: "other" } }, { key: "105", commit: { sha } }] }
            : { projectStatus: { status, conditions } };
        const code = list ? listStatus : gateStatus;
        return { ok: code === 200, status: code, json: async () => body };
    };
    return { fetch, requests };
}

test("reports a passed gate and authenticates with the token", async () => {
    const { fetch, requests } = sonar();
    assert.deepEqual(await fetchGate(gateOptions, fetch), { available: true, passed: true, failedConditions: [] });
    assert.equal(requests[0].url, "https://sonarcloud.io/api/project_pull_requests/list?project=Yukuhu_home-control");
    assert.equal(requests[1].url,
        "https://sonarcloud.io/api/qualitygates/project_status?projectKey=Yukuhu_home-control&pullRequest=105");
    assert.equal(requests[0].options.headers.Authorization, "Bearer secret");
});

test("sends no authorisation header without a token", async () => {
    const { fetch, requests } = sonar();
    await fetchGate({ ...gateOptions, token: "" }, fetch);
    assert.deepEqual(requests[0].options.headers, {});
});

test("reports only the failed conditions of a failed gate", async () => {
    const { fetch } = sonar({ status: "ERROR", conditions: [
        { status: "OK", metricKey: "new_security_rating", comparator: "GT", errorThreshold: "1", actualValue: "1" },
        { status: "ERROR", metricKey: "new_coverage", comparator: "LT", periodIndex: 1, errorThreshold: "80", actualValue: "71.2" },
    ] });
    assert.deepEqual(await fetchGate(gateOptions, fetch), { available: true, passed: false, failedConditions: [
        { metricKey: "new_coverage", comparator: "LT", actualValue: "71.2", errorThreshold: "80" },
    ] });
});

test("an analysis of another commit is unavailable and the gate is not requested", async () => {
    const { fetch, requests } = sonar({ sha: "731bc9f246031fd9f91ced98cd1393cc312813b6" });
    assert.deepEqual(await fetchGate(gateOptions, fetch), { available: false });
    assert.equal(requests.length, 1);
});

test("a pull request SonarCloud has never analysed is unavailable", async () => {
    const { fetch } = sonar();
    assert.deepEqual(await fetchGate({ ...gateOptions, pullRequest: "999" }, fetch), { available: false });
});

test("HTTP errors, timeouts, unexpected shapes and unknown statuses are unavailable", async () => {
    assert.deepEqual(await fetchGate(gateOptions, sonar({ listStatus: 401 }).fetch), { available: false });
    assert.deepEqual(await fetchGate(gateOptions, sonar({ gateStatus: 500 }).fetch), { available: false });
    assert.deepEqual(await fetchGate(gateOptions, sonar({ status: "NONE" }).fetch), { available: false });
    assert.deepEqual(await fetchGate(gateOptions,
        async () => ({ ok: true, status: 200, json: async () => ({ unexpected: true }) })), { available: false });
    const never = (url, { signal }) => new Promise((resolve, reject) =>
        signal.addEventListener("abort", () => reject(signal.reason)));
    assert.deepEqual(await fetchGate({ ...gateOptions, timeoutMs: 20 }, never), { available: false });
});

// ---- render

const SONAR_URL = "https://sonarcloud.io/summary/new_code?id=Yukuhu_home-control&pullRequest=105";
const RUN_URL = "https://github.com/Yukuhu/home-control/actions/runs/36307910825";
const noResults = { found: false, unreadable: 0, passed: 0, failed: 0, skipped: 0, failures: [] };

function suite(values = {}) {
    return { found: true, unreadable: 0, passed: 0, failed: 0, skipped: 0, failures: [], ...values };
}

function failures(count, values = {}) {
    return Array.from({ length: count }, (_, index) => ({
        className: "dev.andre.homecontrol.core.ExampleTest", name: `fails${index}()`,
        message: "expected: <1> but was: <2>", trace: "AssertionFailedError\n\tat Example.java:1", ...values,
    }));
}

function model(overrides = {}, results = {}) {
    const result = (key) => results[key] ?? "success";
    const log = (key) => `https://github.com/Yukuhu/home-control/actions/runs/36307910825/job/${key}`;
    return {
        headSha: HEAD, runUrl: RUN_URL, runNumber: 412, runAttempt: 1, durationSeconds: 663, sonarUrl: SONAR_URL,
        gate: { available: true, passed: true, failedConditions: [] },
        checks: [
            { key: "test", label: "Unit and integration tests", result: result("test"), logUrl: log("test"),
                suite: suite({ passed: 842, skipped: 3 }) },
            { key: "e2e", label: "Browser tests (Chromium, WebKit)", result: result("e2e"), logUrl: log("e2e"),
                suite: suite({ passed: 96 }) },
            { key: "sonar", label: "SonarCloud quality gate", result: result("sonar"), logUrl: log("sonar") },
            { key: "image", label: "Image (amd64)", result: result("image"), logUrl: log("image") },
            { key: "image-arm64", label: "Image smoke test (arm64)", result: result("image-arm64"),
                logUrl: log("image-arm64") },
            { key: "image-arm64-bluetooth", label: "Bluetooth image smoke test (arm64)",
                result: result("image-arm64-bluetooth"), logUrl: log("image-arm64-bluetooth") },
        ],
        ...overrides,
    };
}

function withSuite(base, key, values) {
    return { ...base, checks: base.checks.map((check) => (check.key === key ? { ...check, suite: values } : check)) };
}

test("a green run is the marker, the heading, the commit line and the table", () => {
    assert.equal(render(model()), `${MARKER}
## ✅ CI passed
\` 1277414 \` · [run #412](${RUN_URL}) · 11m 3s

| Check | Result |
|---|---|
| Unit and integration tests | ✅ 842 passed, 3 skipped |
| Browser tests (Chromium, WebKit) | ✅ 96 passed |
| SonarCloud quality gate | ✅ passed · [details](${SONAR_URL}) |
| Image (amd64) | ✅ |
| Image smoke test (arm64) | ✅ |
| Bluetooth image smoke test (arm64) | ✅ |
`);
});

test("the marker is the first line so the comment can be found again", () => {
    assert.ok(render(model({}, { test: "failure" })).startsWith(`${MARKER}\n`));
});

test("a rerun names its attempt, and short runs show seconds only", () => {
    const comment = render(model({ runAttempt: 2, durationSeconds: 42 }));
    assert.match(comment, /\[run #412, attempt 2\]\(.*\) · 42s\n/);
});

test("failed tests are listed with class, name, message and a collapsed trace", () => {
    const base = model({ gate: null }, { test: "failure", sonar: "skipped" });
    const comment = render(withSuite(base, "test", suite({ passed: 840, failed: 2, skipped: 3, failures: failures(2) })));
    assert.match(comment, /^## ❌ CI failed$/m);
    assert.match(comment, /\| Unit and integration tests \| ❌ 2 failed, 840 passed, 3 skipped \|/);
    assert.match(comment, /\| SonarCloud quality gate \| ⏭️ not run, because tests failed \|/);
    assert.ok(comment.includes("### Failed tests\n\n**` ExampleTest `** › ` fails0() `\n\n"
        + "```text\nexpected: <1> but was: <2>\n```\n\n"
        + "<details><summary>Stack trace</summary>\n\n```text\nAssertionFailedError\n\tat Example.java:1\n```\n\n</details>"));
    assert.ok(!comment.includes("### Quality gate"));
});

test("a failed gate lists its conditions with value and threshold", () => {
    const gate = { available: true, passed: false, failedConditions: [
        { metricKey: "new_coverage", comparator: "LT", actualValue: "71.2", errorThreshold: "80" },
        { metricKey: "new_duplicated_lines_density", comparator: "GT", actualValue: "4.5", errorThreshold: "3" },
        { metricKey: "new_reliability_rating", comparator: "GT", actualValue: "3", errorThreshold: "1" },
        { metricKey: "new_maintainability_rating", comparator: "GT", actualValue: "4", errorThreshold: "2" },
        { metricKey: "new_violations", comparator: "GT", actualValue: "3", errorThreshold: "0" },
        { metricKey: "some_new_metric", comparator: "GT", actualValue: "7", errorThreshold: "5" },
        { metricKey: "odd_metric", comparator: "GT", actualValue: "<b>@x", errorThreshold: "5" },
    ] };
    const comment = render(model({ gate }, { sonar: "failure" }));
    assert.match(comment, /\| SonarCloud quality gate \| ❌ failed · \[details\]\(/);
    assert.ok(comment.includes(`### Quality gate

- Coverage on new code: 71.2% (needs ≥ 80%)
- Duplicated lines on new code: 4.5% (needs ≤ 3%)
- Reliability rating on new code: C (needs A)
- Maintainability rating on new code: D (needs B or better)
- New issues: 3 (needs ≤ 0)
- \` some_new_metric \`: 7 (needs ≤ 5)
- \` odd_metric \`: \` <b>@x \` (threshold \` 5 \`)

[Analysis on SonarCloud](${SONAR_URL})
`));
});

test("unavailable gate details fall back to the job result and links", () => {
    const gate = { available: false };
    assert.match(render(model({ gate })),
        /\| SonarCloud quality gate \| ✅ passed · details unavailable · \[SonarCloud\]\([^)]*\) \|/);
    const failed = render(model({ gate }, { sonar: "failure" }));
    assert.match(failed,
        /\| SonarCloud quality gate \| ❌ failed · details unavailable · \[SonarCloud\]\([^)]*\) · \[job log\]\([^)]*\/job\/sonar\) \|/);
    assert.ok(!failed.includes("### Quality gate"));
});

test("a sonar job that failed with a green gate is reported as a job failure", () => {
    const comment = render(model({}, { sonar: "failure" }));
    assert.match(comment, /\| SonarCloud quality gate \| ❌ job failed, gate passed · \[job log\]\(/);
    assert.ok(!comment.includes("### Quality gate"));
});

test("a skipped sonar job without a failed suite is simply not run", () => {
    const comment = render(model({ gate: null }, { image: "failure", sonar: "skipped" }));
    assert.match(comment, /\| SonarCloud quality gate \| ⏭️ not run \|/);
});

test("failed, cancelled and skipped jobs without tests link to their log or say not run", () => {
    const comment = render(model({}, { "image-arm64": "failure", "image-arm64-bluetooth": "skipped", image: "cancelled" }));
    assert.match(comment, /^## ❌ CI failed$/m);
    assert.match(comment, /\| Image smoke test \(arm64\) \| ❌ \[job log\]\([^)]*\/job\/image-arm64\) \|/);
    assert.match(comment, /\| Bluetooth image smoke test \(arm64\) \| ⏭️ not run \|/);
    assert.match(comment, /\| Image \(amd64\) \| ❌ cancelled · \[job log\]\([^)]*\/job\/image\) \|/);
});

test("a cancelled suite job and a cancelled sonar job both read cancelled with a log link", () => {
    const comment = render(model({ gate: null }, { test: "cancelled", sonar: "cancelled" }));
    assert.match(comment, /^## ❌ CI failed$/m);
    assert.match(comment, /\| Unit and integration tests \| ❌ cancelled · \[job log\]\([^)]*\/job\/test\) \|/);
    assert.match(comment, /\| SonarCloud quality gate \| ❌ cancelled · \[job log\]\([^)]*\/job\/sonar\) \|/);
});

test("a missing log link degrades to plain text", () => {
    const base = model({}, { image: "failure" });
    const comment = render({ ...base, checks: base.checks.map((check) => ({ ...check, logUrl: undefined })) });
    assert.match(comment, /\| Image \(amd64\) \| ❌ job log \|/);
});

test("a suite job that failed without results failed before tests ran", () => {
    const comment = render(withSuite(model({ gate: null }, { test: "failure", sonar: "skipped" }), "test", noResults));
    assert.match(comment, /\| Unit and integration tests \| ❌ failed before tests ran · \[job log\]\([^)]*\/job\/test\) \|/);
});

test("a suite job that succeeded without results shows no counts", () => {
    assert.match(render(withSuite(model(), "e2e", noResults)), /\| Browser tests \(Chromium, WebKit\) \| ✅ \|/);
});

test("a suite job that failed although every test passed does not look green", () => {
    const comment = render(model({ gate: null }, { test: "failure", sonar: "skipped" }));
    assert.match(comment,
        /\| Unit and integration tests \| ❌ 842 passed, 3 skipped, but the job failed · \[job log\]\(/);
});

test("unreadable result files are mentioned in the row", () => {
    const comment = render(withSuite(model({ gate: null }, { test: "failure", sonar: "skipped" }), "test",
        suite({ passed: 5, unreadable: 1 })));
    assert.match(comment, /❌ 5 passed, 1 result file unreadable, but the job failed/);
});

test("only the first ten failures are listed", () => {
    const base = model({ gate: null }, { test: "failure", sonar: "skipped" });
    const comment = render(withSuite(base, "test", suite({ failed: 24, failures: failures(24) })));
    assert.ok(comment.includes("` fails9() `"));
    assert.ok(!comment.includes("` fails10() `"));
    assert.ok(comment.includes(`…and 14 more · [full run](${RUN_URL})`));
});

test("failures of both suites are listed together", () => {
    let base = model({ gate: null }, { test: "failure", e2e: "failure", sonar: "skipped" });
    base = withSuite(base, "test", suite({ failed: 1, failures: failures(1, { name: "unit()" }) }));
    base = withSuite(base, "e2e", suite({ failed: 1, failures: failures(1, { name: 'opens(String) ["webkit"]' }) }));
    const comment = render(base);
    assert.ok(comment.includes("` unit() `"));
    assert.ok(comment.includes('` opens(String) ["webkit"] `'));
});

test("long messages and traces are cut", () => {
    const trace = Array.from({ length: 45 }, (_, index) => `\tat line${index}`).join("\n");
    const base = model({ gate: null }, { test: "failure", sonar: "skipped" });
    const comment = render(withSuite(base, "test",
        suite({ failed: 1, failures: failures(1, { message: "m".repeat(400), trace }) })));
    assert.ok(comment.includes(`\n${"m".repeat(299)}…\n`));
    assert.ok(comment.includes("\tat line29\n… 15 more lines\n"));
    assert.ok(!comment.includes("line30"));
});

test("an oversized comment drops its traces, then failures, until it fits", () => {
    const base = model({ gate: null }, { test: "failure", sonar: "skipped" });
    const wide = "x".repeat(8000);
    const traces = Array.from({ length: 30 }, () => wide).join("\n");
    const withoutTraces = render(withSuite(base, "test",
        suite({ failed: 10, failures: failures(10, { trace: traces }) })));
    assert.ok(withoutTraces.length <= 60000);
    assert.ok(!withoutTraces.includes("Stack trace"));
    assert.ok(withoutTraces.includes("` fails9() `"));

    const names = render(withSuite(base, "test",
        suite({ failed: 10, failures: failures(10, { trace: "" }).map((entry) => ({ ...entry, name: "n".repeat(9000) })) })));
    assert.ok(names.length <= 60000);
    assert.match(names, /…and [4-9] more/);
});

test("hostile test output cannot leave its code spans and blocks", async () => {
    const hostile = parseJUnit(await fixture("hostile.xml"));
    const base = model({ gate: null }, { test: "failure", sonar: "skipped" });
    const comment = render(withSuite(base, "test", suite({ failed: 1, failures: hostile.failures })));
    assert.ok(comment.includes("**` <img src=x>HostileTest `** › ``` a`b``c() @Yukuhu ```"));
    // The message contains a three-backtick run and the trace a four-backtick run.
    assert.ok(comment.includes("\n````text\n```\n</details> @Yukuhu **bold** <script>alert(1)</script>\n````\n"));
    assert.ok(comment.includes("\n`````text\nfirst\n````\n</details> @Yukuhu [link](https://example.com)\n`````\n"));
    // Outside code, only our own markup remains.
    const outside = comment.replace(/(`{3,})text\n[\s\S]*?\n\1\n/g, "").replace(/(`+) .*? \1/g, "");
    assert.ok(!outside.includes("@"));
    assert.ok(!outside.includes("<script"));
    assert.equal(outside.match(/<\/details>/g).length, 1);
});

// ---- buildModel and main

const environment = {
    NEEDS_JSON: JSON.stringify({
        test: { result: "failure", outputs: {} }, e2e: { result: "success", outputs: {} },
        sonar: { result: "skipped", outputs: {} }, image: { result: "success", outputs: {} },
        "image-arm64": { result: "success", outputs: {} }, "image-arm64-bluetooth": { result: "success", outputs: {} },
    }),
    JOBS_JSON: JSON.stringify([
        { name: "Build and test", html_url: "https://example.test/job/1" },
        { name: "Smoke-test the Bluetooth image on arm64", html_url: "https://example.test/job/6" },
    ]),
    HEAD_SHA: HEAD, PR_NUMBER: "105", RUN_URL, RUN_NUMBER: "413", RUN_ATTEMPT: "2",
    RUN_STARTED_AT: "2026-09-27T08:59:06Z", SONAR_PROJECT_KEY: "Yukuhu_home-control",
};

test("the model is built from the needs context, the job list and the environment", () => {
    const suites = { test: suite({ failed: 1 }), e2e: suite({ passed: 96 }) };
    const built = buildModel({ env: environment, suites, gate: null, now: Date.parse("2026-09-27T09:08:46Z") });
    assert.equal(built.headSha, HEAD);
    assert.equal(built.runNumber, 413);
    assert.equal(built.runAttempt, 2);
    assert.equal(built.durationSeconds, 580);
    assert.equal(built.sonarUrl, SONAR_URL);
    assert.deepEqual(built.checks.map((check) => [check.key, check.result]), [
        ["test", "failure"], ["e2e", "success"], ["sonar", "skipped"], ["image", "success"],
        ["image-arm64", "success"], ["image-arm64-bluetooth", "success"],
    ]);
    assert.equal(built.checks[0].logUrl, "https://example.test/job/1");
    assert.equal(built.checks[1].logUrl, undefined);
    assert.equal(built.checks[5].logUrl, "https://example.test/job/6");
    assert.equal(built.checks[0].suite, suites.test);
    assert.equal(built.checks[2].suite, undefined);
});

test("a job missing from the needs context counts as not run, and a missing start time as zero", () => {
    const env = { ...environment, NEEDS_JSON: "{}", JOBS_JSON: "", RUN_STARTED_AT: "", RUN_ATTEMPT: "" };
    const built = buildModel({ env, suites: { test: noResults, e2e: noResults }, gate: null, now: 0 });
    assert.ok(built.checks.every((check) => check.result === "skipped"));
    assert.equal(built.durationSeconds, 0);
    assert.equal(built.runAttempt, 1);
});

test("main writes the comment and asks SonarCloud only when the sonar job ran", async (t) => {
    const root = await directory(t, "failed.xml");
    const env = { ...environment, JUNIT_TEST_DIR: root, JUNIT_E2E_DIR: path.join(root, "absent"),
        SUMMARY_FILE: path.join(root, "summary.md"), SONAR_TOKEN: "secret" };
    let requests = 0;
    const fetch = async (url) => {
        requests++;
        return { ok: true, status: 200, json: async () => (url.includes("list")
            ? { pullRequests: [{ key: "105", commit: { sha: HEAD } }] }
            : { projectStatus: { status: "OK", conditions: [] } }) };
    };
    await main(env, fetch);
    assert.equal(requests, 0);
    const skipped = await readFile(env.SUMMARY_FILE, "utf8");
    assert.match(skipped, /\| Unit and integration tests \| ❌ 3 failed, 1 passed \|/);
    assert.match(skipped, /\| Browser tests \(Chromium, WebKit\) \| ✅ \|/);
    assert.match(skipped, /⏭️ not run, because tests failed/);

    const needs = { ...JSON.parse(env.NEEDS_JSON), test: { result: "success" }, sonar: { result: "success" } };
    await main({ ...env, NEEDS_JSON: JSON.stringify(needs), JUNIT_TEST_DIR: path.join(root, "absent") }, fetch);
    assert.equal(requests, 2);
    assert.match(await readFile(env.SUMMARY_FILE, "utf8"), /^## ✅ CI passed$/m);
});
