import assert from "node:assert/strict";
import { mkdtemp, mkdir, copyFile, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { collectSuite, parseJUnit } from "./summary.mjs";

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