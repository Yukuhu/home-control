import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { convertCoverage } from "./report.mjs";

const source = 'export function choose(flag) {\n  if (flag) return "yes";\n  return "no";\n}\n';

async function fixture(t) {
    const root = await mkdtemp(path.join(tmpdir(), "home-control-coverage-"));
    t.after(() => rm(root, { recursive: true, force: true }));
    const scripts = path.join(root, "src/main/resources/static/js");
    const rawDirectory = path.join(root, "raw");
    const reportDirectory = path.join(root, "report");
    await mkdir(scripts, { recursive: true });
    await mkdir(rawDirectory);
    await writeFile(path.join(scripts, "choice.js"), source);
    await writeFile(path.join(scripts, "unvisited.js"), 'console.log("not visited");\n');
    return { root, rawDirectory, reportDirectory };
}

function entry(uncoveredLine) {
    const start = source.indexOf(uncoveredLine);
    return {
        url: "http://localhost:1234/js/choice.js?v=1",
        source,
        functions: [{ functionName: "choose", isBlockCoverage: true, ranges: [
            { startOffset: 0, endOffset: source.length, count: 1 },
            { startOffset: start, endOffset: start + uncoveredLine.length, count: 0 },
        ] }],
    };
}

test("merges test sessions and navigations, includes unvisited scripts, and uses repository paths", async (t) => {
    const options = await fixture(t);
    await writeFile(path.join(options.rawDirectory, "one.json"), JSON.stringify([
        entry('  return "no";'),
        { url: "http://localhost:1234/vendor/htmx.js", functions: [] },
    ]));
    await writeFile(path.join(options.rawDirectory, "two.json"), JSON.stringify([
        entry('  if (flag) return "yes";'),
    ]));
    await convertCoverage(options);
    const lcov = await readFile(path.join(options.reportDirectory, "lcov.info"), "utf8");
    assert.match(lcov, /SF:src\/main\/resources\/static\/js\/choice\.js/);
    const choice = lcov.split("end_of_record").find((record) => record.includes("/choice.js"));
    assert.match(choice, /DA:2,[1-9]\d*/);
    assert.match(choice, /DA:3,[1-9]\d*/);
    const unvisited = lcov.split("end_of_record").find((record) => record.includes("/unvisited.js"));
    assert.match(unvisited, /DA:1,0/);
    assert.doesNotMatch(lcov, /htmx/);
    assert.doesNotMatch(lcov, new RegExp(options.root));
});

test("rejects coverage from a different source revision", async (t) => {
    const options = await fixture(t);
    await writeFile(path.join(options.rawDirectory, "stale.json"), JSON.stringify([
        { ...entry('  return "no";'), source: "different source" },
    ]));
    await assert.rejects(convertCoverage(options), /source differs/);
});

test("fails when no application coverage was captured", async (t) => {
    const options = await fixture(t);
    await writeFile(path.join(options.rawDirectory, "empty.json"), "[]");
    await assert.rejects(convertCoverage(options), /No application JavaScript coverage/);
});
