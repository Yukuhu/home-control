import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { XMLParser, XMLValidator } from "fast-xml-parser";

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
    const result = { passed: 0, failed: 0, skipped: 0, failures: [] };
    for (const suite of suites) {
        for (const testcase of suite.testcase ?? []) {
            const problem = [testcase.failure, testcase.error].flat().find((entry) => entry !== undefined);
            if (problem !== undefined) {
                result.failed++;
                result.failures.push(failure(testcase, problem));
            } else if (testcase.skipped !== undefined) {
                result.skipped++;
            } else {
                result.passed++;
            }
        }
    }
    return result;
}

function failure(testcase, problem) {
    const trace = String(typeof problem === "string" ? problem : problem["#text"] ?? "").trim();
    const stated = typeof problem === "string" ? "" : String(problem["@message"] ?? "").trim();
    const message = stated || trace.split("\n")[0] || "No failure message";
    return { className: String(testcase["@classname"] ?? ""), name: String(testcase["@name"] ?? ""), message, trace };
}

export async function collectSuite(directory) {
    const result = { found: false, unreadable: 0, passed: 0, failed: 0, skipped: 0, failures: [] };
    let names;
    try {
        names = (await readdir(directory, { recursive: true }))
            .filter((name) => /(^|[\\/])TEST-[^\\/]*\.xml$/.test(name)).sort();
    } catch (error) {
        if (error.code === "ENOENT") return result;
        throw error;
    }
    for (const name of names) {
        result.found = true;
        try {
            const parsed = parseJUnit(await readFile(path.join(directory, name), "utf8"));
            result.passed += parsed.passed;
            result.failed += parsed.failed;
            result.skipped += parsed.skipped;
            result.failures.push(...parsed.failures);
        } catch {
            // A test JVM that died mid-write leaves a truncated file; the others still count.
            result.unreadable++;
        }
    }
    return result;
}

const UNAVAILABLE = { available: false };

export async function fetchGate({ projectKey, pullRequest, headSha, token, timeoutMs = 10000 }, fetch = globalThis.fetch) {
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
