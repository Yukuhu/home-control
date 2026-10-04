import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { XMLParser, XMLValidator } from "fast-xml-parser";

export const MARKER = "<!-- home-control-ci-summary -->";

const MAX_COMMENT = 60000;
const MAX_FAILURES = 10;
const MAX_MESSAGE = 300;
const MAX_TRACE_LINES = 30;
const REPORT_READ_BATCH_SIZE = 4;

// The row order of the comment. `job` is the job's display name in ci.yml, which is how the
// GitHub API identifies it; `suite` names the JUnit results that belong to the job. A job that
// runs several times is named "<job> on <architecture>" or "<job> (<language>)" there.
export const CHECKS = [
    { key: "checksums", job: "Verify dependency checksums", label: "Dependency checksums" },
    { key: "checksum-update", job: "Update dependency checksums", label: "Update dependency checksums" },
    { key: "jar", job: "Build the jar", label: "Jar" },
    { key: "test", job: "Build and test", label: "Unit and integration tests", suite: "test" },
    { key: "e2e-chromium", job: "Browser tests (Chromium)", label: "Browser tests (Chromium)", suite: "e2e-chromium" },
    { key: "e2e-firefox", job: "Browser tests (Firefox)", label: "Browser tests (Firefox)", suite: "e2e-firefox" },
    { key: "e2e-webkit", job: "Browser tests (WebKit)", label: "Browser tests (WebKit)", suite: "e2e-webkit" },
    { key: "sonar", job: "SonarCloud quality gate", label: "SonarCloud quality gate" },
    { key: "image", job: "Build the self-contained image", label: "Image built from source (amd64)" },
    { key: "smoke", job: "Smoke-test the image", label: "Image smoke test (amd64, arm64)" },
    { key: "smoke-bluetooth", job: "Smoke-test the Bluetooth image",
        label: "Bluetooth image smoke test (amd64, arm64)" },
    { key: "dependencies", job: "Dependency vulnerabilities", label: "Dependency vulnerabilities" },
    { key: "codeql", job: "Scan the code", label: "Code scan (CodeQL)" },
];

const METRICS = {
    new_coverage: "Coverage on new code",
    new_duplicated_lines_density: "Duplicated lines on new code",
    new_security_hotspots_reviewed: "Security hotspots reviewed on new code",
    new_reliability_rating: "Reliability rating on new code",
    new_security_rating: "Security rating on new code",
    new_maintainability_rating: "Maintainability rating on new code",
    new_violations: "New issues",
};

const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@",
    parseTagValue: false,
    isArray: (name) => name === "testsuite" || name === "testcase",
    // Gradle writes line breaks in failure messages as numeric references such as &#10;.
    htmlEntities: true,
    // Captured output can run to megabytes and is never shown.
    updateTag: (name) => (name === "system-out" || name === "system-err" ? false : name),
});

export function parseJUnit(xml) {
    const valid = XMLValidator.validate(xml);
    if (valid !== true) throw new Error(`Malformed JUnit XML: ${valid.err.msg}`);
    const document = parser.parse(xml);
    const suites = document.testsuites?.testsuite ?? document.testsuite;
    if (!suites) throw new Error("Malformed JUnit XML: no testsuite element");
    const result = { passed: 0, failed: 0, skipped: 0, failures: [], flaky: [] };
    for (const suite of suites) {
        for (const testcase of suite.testcase ?? []) {
            const problem = [testcase.failure, testcase.error].flat().find((entry) => entry !== undefined);
            // Gradle merges the attempts of a retried test into one testcase: the failed attempts
            // of a test that passed in the end are its flakyFailure elements.
            const retried = [testcase.flakyFailure].flat().find((entry) => entry !== undefined);
            if (problem !== undefined) {
                result.failed++;
                result.failures.push(failure(testcase, problem));
            } else if (testcase.skipped !== undefined) {
                result.skipped++;
            } else {
                result.passed++;
                if (retried !== undefined) result.flaky.push(failure(testcase, retried));
            }
        }
    }
    return result;
}

function failure(testcase, problem) {
    // A failure holds its trace as text, the failed attempt of a retried test in a stackTrace element.
    const trace = String(typeof problem === "string" ? problem : problem["#text"] ?? problem.stackTrace ?? "").trim();
    const stated = typeof problem === "string" ? "" : String(problem["@message"] ?? "").trim();
    const message = stated || trace.split("\n")[0] || "No failure message";
    return { className: String(testcase["@classname"] ?? ""), name: String(testcase["@name"] ?? ""), message, trace };
}

export async function collectSuite(directory) {
    const result = { found: false, unreadable: 0, passed: 0, failed: 0, skipped: 0, failures: [], flaky: [] };
    let names;
    try {
        names = (await readdir(directory, { recursive: true }))
            .filter((name) => /(^|[\\/])TEST-[^\\/]*\.xml$/.test(name)).sort();
    } catch (error) {
        if (error.code === "ENOENT") return result;
        throw error;
    }
    result.found = names.length > 0;
    for (let index = 0; index < names.length; index += REPORT_READ_BATCH_SIZE) {
        const batch = names.slice(index, index + REPORT_READ_BATCH_SIZE);
        const reports = await Promise.all(batch.map(async (name) => { // NOSONAR: Limit concurrent reads of potentially large JUnit files to four.
            try {
                return parseJUnit(await readFile(path.join(directory, name), "utf8"));
            } catch {
                // A test JVM that died mid-write leaves a truncated file; the others still count.
                return null;
            }
        }));
        for (const parsed of reports) {
            if (parsed === null) {
                result.unreadable++;
                continue;
            }
            result.passed += parsed.passed;
            result.failed += parsed.failed;
            result.skipped += parsed.skipped;
            result.failures.push(...parsed.failures);
            result.flaky.push(...parsed.flaky);
        }
    }
    return result;
}

const UNAVAILABLE = { available: false };

export async function fetchGate(
    { projectKey, pullRequest, headSha, token, timeoutMs = 10000 },
    fetch = globalThis.fetch,
) {
    const get = async (pathAndQuery) => {
        const response = await fetch(`https://sonarcloud.io/api/${pathAndQuery}`, {
            headers: token ? { Authorization: `Bearer ${token}` } : {},
            signal: AbortSignal.timeout(timeoutMs),
        });
        if (!response.ok) throw new Error(`SonarCloud answered ${response.status}`);
        return response.json();
    };
    try {
        const project = encodeURIComponent(projectKey);
        const key = encodeURIComponent(pullRequest);
        const list = await get(`project_pull_requests/list?project=${project}`);
        const analysed = list.pullRequests.find((entry) => entry.key === String(pullRequest));
        // SonarCloud records the pull request's head commit, not the merge commit Actions checks out.
        if (analysed?.commit?.sha !== headSha) return UNAVAILABLE;
        const { projectStatus } = await get(`qualitygates/project_status?projectKey=${project}&pullRequest=${key}`);
        if (projectStatus.status !== "OK" && projectStatus.status !== "ERROR") return UNAVAILABLE;
        return {
            available: true,
            passed: projectStatus.status === "OK",
            failedConditions: projectStatus.conditions
                .filter((condition) => condition.status === "ERROR")
                .map(({ metricKey, comparator, actualValue, errorThreshold }) =>
                    ({ metricKey, comparator, actualValue, errorThreshold })),
        };
    } catch {
        return UNAVAILABLE;
    }
}

// Untrusted text is only ever rendered as code, where it cannot produce markup or mentions.
function code(text) {
    const flat = String(text).replace(/\s+/g, " ").trim() || " ";
    const ticks = "`".repeat(longestRun(flat) + 1);
    return `${ticks} ${flat} ${ticks}`;
}

function block(text) {
    const ticks = "`".repeat(Math.max(3, longestRun(text) + 1));
    return `${ticks}text\n${text}\n${ticks}`;
}

function longestRun(text) {
    return Math.max(0, ...(String(text).match(/`+/g) ?? []).map((run) => run.length));
}

function shorten(text, limit) {
    return text.length > limit ? `${text.slice(0, limit - 1)}…` : text;
}

function counts(suite) {
    const parts = [];
    if (suite.failed > 0) parts.push(`${suite.failed} failed`);
    parts.push(`${suite.passed} passed`);
    if (suite.flaky.length > 0) parts.push(`${suite.flaky.length} of them only on a retry`);
    if (suite.skipped > 0) parts.push(`${suite.skipped} skipped`);
    if (suite.unreadable > 0) {
        parts.push(`${suite.unreadable} result ${suite.unreadable === 1 ? "file" : "files"} unreadable`);
    }
    return parts.join(", ");
}

function link(text, url) {
    return url ? `[${text}](${url})` : text;
}

function suiteRow(check) {
    const { result, suite, logUrl } = check;
    const log = link("job log", logUrl);
    if (result === "success" && suite.flaky.length > 0) return `⚠️ ${counts(suite)}`;
    if (result === "success") return suite.found ? `✅ ${counts(suite)}` : "✅";
    if (result !== "failure") return "⏭️ not run";
    if (!suite.found) return `❌ failed before tests ran · ${log}`;
    if (suite.failed > 0) return `❌ ${counts(suite)}`;
    return `❌ ${counts(suite)}, but the job failed · ${log}`;
}

function sonarRow(check, model) {
    const { result, logUrl } = check;
    const gate = model.gate ?? UNAVAILABLE;
    if (result === "success" || result === "failure") {
        const state = result === "success" ? "✅ passed" : "❌ failed";
        const log = result === "failure" ? ` · ${link("job log", logUrl)}` : "";
        if (!gate.available) return `${state} · details unavailable · ${link("SonarCloud", model.sonarUrl)}${log}`;
        // The job can fail with the gate green, for instance when the scanner itself breaks.
        if (result === "failure" && gate.passed) return `❌ job failed, gate passed${log}`;
        return `${state} · ${link("details", model.sonarUrl)}`;
    }
    const failedSuites = model.checks.filter((other) => other.suite && other.result === "failure");
    if (failedSuites.length === 0) return "⏭️ not run";
    const testsFailed = failedSuites.some((other) => other.suite.failed > 0);
    return testsFailed ? "⏭️ not run, because tests failed" : "⏭️ not run, because an earlier job failed";
}

function row(check, model) {
    // A job cancelled or timed out while the run continued must still surface as a failure;
    // only `skipped` (the run never reached it) keeps the "not run" rendering below.
    if (check.result === "cancelled") return `❌ cancelled · ${link("job log", check.logUrl)}`;
    if (check.key === "sonar") return sonarRow(check, model);
    if (check.suite) return suiteRow(check);
    if (check.result === "success") return "✅";
    if (check.result === "failure") return `❌ ${link("job log", check.logUrl)}`;
    return "⏭️ not run";
}

function rating(value) {
    return "ABCDE"[Math.round(Number(value)) - 1];
}

function condition({ metricKey, comparator, actualValue, errorThreshold }) {
    const name = METRICS[metricKey] ?? code(metricKey);
    if (!Number.isFinite(Number(actualValue)) || !Number.isFinite(Number(errorThreshold))) {
        return `- ${name}: ${code(actualValue)} (threshold ${code(errorThreshold)})`;
    }
    if (metricKey.endsWith("_rating") && rating(actualValue) && rating(errorThreshold)) {
        const needed = rating(errorThreshold) === "A" ? "A" : `${rating(errorThreshold)} or better`;
        return `- ${name}: ${rating(actualValue)} (needs ${needed})`;
    }
    const unit = /coverage|density|reviewed/.test(metricKey) ? "%" : "";
    const bound = comparator === "LT" ? "≥" : "≤";
    return `- ${name}: ${Number(actualValue)}${unit} (needs ${bound} ${Number(errorThreshold)}${unit})`;
}

function failedTest({ className, name, message, trace }, traces) {
    const lines = [`**${code(className.split(".").pop())}** › ${code(name)}`, "", block(shorten(message, MAX_MESSAGE))];
    if (traces && trace) {
        const all = trace.split("\n");
        const shown = all.slice(0, MAX_TRACE_LINES);
        if (all.length > shown.length) shown.push(`… ${all.length - shown.length} more lines`);
        lines.push("", "<details><summary>Stack trace</summary>", "", block(shown.join("\n")), "", "</details>");
    }
    return lines.join("\n");
}

function duration(seconds) {
    const whole = Math.max(0, Math.round(seconds));
    return whole < 60 ? `${whole}s` : `${Math.floor(whole / 60)}m ${whole % 60}s`;
}

function tests(heading, kind, model, { traces, limit }) {
    const groups = model.checks.filter((check) => check.suite?.[kind].length > 0);
    if (groups.length === 0) return [];
    const lines = ["", `### ${heading}`];
    for (const check of groups) {
        lines.push("", `#### ${check.label}`);
        const entries = check.suite[kind];
        for (const entry of entries.slice(0, limit)) lines.push("", failedTest(entry, traces));
        if (entries.length > limit) {
            lines.push("", `…and ${entries.length - limit} more · ${link("full run", model.runUrl)}`);
        }
    }
    return lines;
}

function compose(model, { traces, limit }) {
    // Only when nothing but documentation changed are checks left out on purpose.
    const updating = model.checksumState === "candidate"
        && !model.checks.some((check) => check.result === "failure" || check.result === "cancelled");
    const passed = model.checksumState !== "candidate" && model.checks.every((check) => check.result === "success"
        || (model.documentationOnly && check.result === "skipped"));
    const attempt = model.runAttempt > 1 ? `, attempt ${model.runAttempt}` : "";
    const run = `run #${model.runNumber}${attempt}`;
    const lines = [
        MARKER,
        updating ? "## 🔄 Updating dependency checksums" : passed ? "## ✅ CI passed" : "## ❌ CI failed",
        `${code(model.headSha.slice(0, 7))} · ${link(run, model.runUrl)} · ${duration(model.durationSeconds)}`,
        ...(model.documentationOnly ? ["", "Only documentation changed, so nothing was built or tested."] : []),
        "",
        "| Check | Result |",
        "|---|---|",
        ...model.checks.map((check) => `| ${check.label} | ${row(check, model)} |`),
    ];
    if (model.checksumState === "candidate") {
        const update = model.checksumUpdate;
        if (update?.state === "committed") {
            lines.push("", `Committed verified checksums as ${code(update.commit_sha)}. A new CI run checks this commit.`);
        } else if (update?.state === "superseded") {
            lines.push("", "The PR changed during verification; this run did not update its branch.");
        } else {
            lines.push("", "CI verifies the new artifacts and commits their checksums to this PR. See the update job for progress or failure details.");
        }
    }
    lines.push(...tests("Failed tests", "failures", model, { traces, limit }),
        ...tests("Passed only on a retry", "flaky", model, { traces, limit }));
    const sonar = model.checks.find((check) => check.key === "sonar");
    if (sonar?.result === "failure" && model.gate?.available && !model.gate.passed) {
        lines.push("", "### Quality gate", "", ...model.gate.failedConditions.map(condition),
            "", link("Analysis on SonarCloud", model.sonarUrl));
    }
    return `${lines.join("\n")}\n`;
}

export function render(model) {
    let options = { traces: true, limit: MAX_FAILURES };
    let comment = compose(model, options);
    if (comment.length > MAX_COMMENT) {
        options = { ...options, traces: false };
        comment = compose(model, options);
    }
    while (comment.length > MAX_COMMENT && options.limit > 0) {
        options = { ...options, limit: options.limit - 1 };
        comment = compose(model, options);
    }
    return comment;
}

// Of a job that ran several times, the log worth opening is that of a run that failed.
function logUrl(jobs, job) {
    const runs = jobs.filter((entry) => entry.name === job || entry.name.startsWith(`${job} on `)
        || entry.name.startsWith(`${job} (`));
    return (runs.find((entry) => entry.conclusion === "failure") ?? runs[0])?.html_url;
}

export function buildModel({ env, suites, gate, now }) {
    const needs = JSON.parse(env.NEEDS_JSON);
    const jobs = JSON.parse(env.JOBS_JSON || "[]");
    const started = Date.parse(env.RUN_STARTED_AT);
    return {
        headSha: env.HEAD_SHA,
        runUrl: env.RUN_URL,
        runNumber: Number(env.RUN_NUMBER),
        runAttempt: Number(env.RUN_ATTEMPT || 1),
        documentationOnly: env.CODE_CHANGED === "false",
        durationSeconds: Number.isNaN(started) ? 0 : (now - started) / 1000,
        sonarUrl: `https://sonarcloud.io/summary/new_code?id=${encodeURIComponent(env.SONAR_PROJECT_KEY)}`
            + `&pullRequest=${encodeURIComponent(env.PR_NUMBER)}`,
        gate,
        checksumState: needs.checksums?.outputs?.state,
        checksumUpdate: needs["checksum-update"]?.outputs,
        checks: CHECKS.filter(({ key }) => key !== "checksum-update"
            || (needs[key] && needs[key].result !== "skipped")).map(({ key, job, label, suite }) => ({
            key,
            label,
            result: needs[key]?.result ?? "skipped",
            logUrl: logUrl(jobs, job),
            ...(suite ? { suite: suites[suite] } : {}),
        })),
    };
}

export async function main(env, fetch = globalThis.fetch) {
    const suites = {
        test: await collectSuite(env.JUNIT_TEST_DIR),
        "e2e-chromium": await collectSuite(env.JUNIT_E2E_CHROMIUM_DIR),
        "e2e-firefox": await collectSuite(env.JUNIT_E2E_FIREFOX_DIR),
        "e2e-webkit": await collectSuite(env.JUNIT_E2E_WEBKIT_DIR),
    };
    const sonar = JSON.parse(env.NEEDS_JSON).sonar?.result;
    const gate = sonar === "success" || sonar === "failure"
        ? await fetchGate({ projectKey: env.SONAR_PROJECT_KEY, pullRequest: env.PR_NUMBER,
            headSha: env.HEAD_SHA, token: env.SONAR_TOKEN }, fetch)
        : null;
    await writeFile(env.SUMMARY_FILE, render(buildModel({ env, suites, gate, now: Date.now() })));
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
    await main(process.env);
}
