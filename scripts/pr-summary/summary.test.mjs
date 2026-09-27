import assert from "node:assert/strict";
import { mkdtemp, mkdir, copyFile, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { collectSuite, fetchGate, parseJUnit } from "./summary.mjs";

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
