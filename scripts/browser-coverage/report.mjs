import { readdir, readFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import v8ToIstanbul from "v8-to-istanbul";
import coverage from "istanbul-lib-coverage";
import reporting from "istanbul-lib-report";
import reports from "istanbul-reports";

async function applicationScripts(root) {
    const directory = path.join(root, "src/main/resources/static");
    const names = (await readdir(path.join(directory, "js")))
        .filter((name) => name.endsWith(".js")).map((name) => `js/${name}`);
    if ((await readdir(directory)).includes("sw.js")) names.push("sw.js");
    const scripts = await Promise.all(names.map(async (name) => {
        const file = path.join(directory, name);
        return [`/${name}`, { file, source: await readFile(file, "utf8") }];
    }));
    return new Map(scripts);
}

async function convert(script, functions) {
    const converter = v8ToIstanbul(script.file, 0, { source: script.source });
    await converter.load();
    converter.applyCoverage(functions);
    return converter.toIstanbul();
}

function executed(entry) {
    return entry.functions.some(fn => fn.ranges.some(range => range.count > 0));
}

async function mergeCaptures(scripts, merged, rawDirectory) {
    let captured = 0;
    let workerCaptured = false;
    const files = (await readdir(rawDirectory)).filter((name) => name.endsWith(".json"));
    for (const name of files) {
        const entries = JSON.parse(await readFile(path.join(rawDirectory, name), "utf8")); // NOSONAR: Keep only one raw capture file in memory.
        for (const entry of entries) {
            const script = scripts.get(new URL(entry.url).pathname);
            if (!script) continue;
            if (entry.source !== script.source) throw new Error(`Coverage source differs from ${script.file}`);
            merged.merge(await convert(script, entry.functions)); // NOSONAR: Bound converter memory and update the shared coverage map in order.
            captured++;
            if (new URL(entry.url).pathname === "/sw.js" && executed(entry)) workerCaptured = true;
        }
    }
    return { captured, workerCaptured };
}

export async function convertCoverage({ root, rawDirectory, reportDirectory }) {
    const scripts = await applicationScripts(root);
    const merged = coverage.createCoverageMap({});
    // Files that no test loaded must still count as uncovered.
    for (const script of scripts.values()) {
        const functions = [{ functionName: "", isBlockCoverage: true,
            ranges: [{ startOffset: 0, endOffset: script.source.length, count: 0 }] }];
        merged.merge(await convert(script, functions)); // NOSONAR: Convert one script at a time to bound converter memory.
    }
    const { captured, workerCaptured } = await mergeCaptures(scripts, merged, rawDirectory);
    if (captured === 0) throw new Error("No application JavaScript coverage was captured");
    if (scripts.has("/sw.js") && !workerCaptured) throw new Error("No service worker JavaScript coverage was captured");
    await mkdir(reportDirectory, { recursive: true });
    const context = reporting.createContext({ dir: reportDirectory, coverageMap: merged });
    reports.create("lcovonly", { projectRoot: root }).execute(context);
    reports.create("text-summary").execute(context);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
    const root = fileURLToPath(new URL("../../", import.meta.url));
    await convertCoverage({ root,
        rawDirectory: path.join(root, "build/coverage/browser-raw"),
        reportDirectory: path.join(root, "build/reports/browser-coverage") });
}
