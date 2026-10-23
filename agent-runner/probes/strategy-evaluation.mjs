#!/usr/bin/env node
/** Opt-in launcher. Never invoked by npm test/check; never builds or installs anything.
 *
 *  node agent-runner/probes/strategy-evaluation.mjs /absolute/config.json
 *
 * Required JSON keys:
 * { arm:"candidate", mode:"prepare"|"run"|"scripted-smoke",
 *   sourceRoot:"/checkout", runtimeClasspathFile:"/build/atlas-runtime-classpath.txt",
 *   javaExecutable:"/jdk/bin/java", nodeExecutable:"/node", daemonJs:"/checkout/agent-runner/dist/daemon.js",
 *   outputDirectory:"/isolated/eval-01", fixtureId:"stable-border", seed:1939,
 *   maxOwnTurns:8, maxGameRounds:8, maxAdmittedInvocations:40,
 *   profile:{provider:"codex",model:"<exact model>",reasoning:"<effort>"} }
 * Optional: profiles keyed by civ ID, vision (FULL/FOG), maxAdmissionMinutes,
 * checkpointBundle + expectedCheckpointSha256, messageImagesDirectory, engineSourceCommit.
 * Copied stalled matches opt in to sessionPolicy:{mode:"forked-persistent",
 * forkFromSessionIds:{Russia:"<source-id>",America:"<source-id>",China:"<source-id>"}}.
 * Their first turns use native Codex fork; later turns resume only the new IDs.
 * Compaction stays on; the probe waits for full natural exit between turns.
 *
 * Prepare one fixture once; feed its initial.unciv-agent plus SHA to BOTH arms.
 * Distinct output directories and fresh sessions isolate copied match IDs/receipts.
 * Baseline must use baseline production classes AND baseline runner dist. The same
 * harness may be compiled into both classpaths. The launcher records actual inputs.
 *
 * Stop admission by creating OUTPUT/stop-admission. Active calls finish naturally.
 * Poll OUTPUT/launch.json, jvm.log, host-events.jsonl and providers/RUN/meta.json.
 * No signal, cancellation, stdin control or provider timeout is used here.
 */
import { createHash } from "node:crypto";
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, openSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { basename, dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const input = process.argv[2];
if (!input || !isAbsolute(input)) throw new Error("Pass an absolute evaluation config JSON path");
const config = JSON.parse(readFileSync(input, "utf8"));
for (const key of ["sourceRoot", "runtimeClasspathFile", "javaExecutable", "nodeExecutable", "daemonJs", "outputDirectory"])
  if (typeof config[key] !== "string" || !isAbsolute(config[key])) throw new Error(`${key} must be an absolute path`);
for (const key of ["checkpointBundle", "messageImagesDirectory"])
  if (config[key] != null && !isAbsolute(config[key])) throw new Error(`${key} must be an absolute path`);
if (!["prepare", "run", "scripted-smoke"].includes(config.mode)) throw new Error("Explicit mode required: prepare, run, or scripted-smoke");
if (config.sessionPolicy != null) {
  if (config.sessionPolicy.mode !== "forked-persistent") throw new Error("Unknown evaluation session policy");
  if (!config.checkpointBundle || config.mode === "scripted-smoke") throw new Error("Forked sessions require a copied checkpoint and a prepare/run evaluation");
  const sources = config.sessionPolicy.forkFromSessionIds;
  if (!sources || typeof sources !== "object" || Array.isArray(sources) || !Object.keys(sources).length ||
      Object.values(sources).some(id => typeof id !== "string" || !id.trim())) throw new Error("forkFromSessionIds must map civilizations to source session IDs");
  config.evaluationDriverJs ??= fileURLToPath(new URL("./strategy-evaluation-driver.mjs", import.meta.url));
  if (!isAbsolute(config.evaluationDriverJs) || !existsSync(config.evaluationDriverJs)) throw new Error("Existing absolute evaluationDriverJs is required");
}
const output = resolve(config.outputDirectory);
if (existsSync(output)) throw new Error("Use a new outputDirectory for every evaluation; never overwrite evidence");
if (config.checkpointBundle) {
  const checkpoint = realpathSync(config.checkpointBundle);
  let sourceState = dirname(checkpoint);
  for (let parent = sourceState; dirname(parent) !== parent; parent = dirname(parent)) {
    if (basename(parent) === ".agent-data") { sourceState = dirname(parent); break; }
  }
  let ancestor = output;
  const missing = [];
  while (!existsSync(ancestor)) { missing.unshift(basename(ancestor)); ancestor = dirname(ancestor); }
  const actualOutput = join(realpathSync(ancestor), ...missing);
  const relation = relative(sourceState, actualOutput);
  if (!relation || (!relation.startsWith("..") && !isAbsolute(relation))) throw new Error("Output must be outside source checkpoint/userdata tree");
}
const classpath = readFileSync(config.runtimeClasspathFile, "utf8").trim();
if (!classpath) throw new Error("Runtime classpath file is empty; generate it through the isolated build workflow first");
const sourceRoot = realpathSync(config.sourceRoot);
const cwd = join(sourceRoot, "android", "assets");
const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");
const git = (args) => execFileSync("git", ["-C", sourceRoot, ...args], { maxBuffer: 64 * 1024 * 1024 });
const sourceFiles = git(["ls-files", "-z", "--cached", "--others", "--exclude-standard", "core/src", "desktop/src", "agent-runner/src", "agent-runner/probes/strategy-evaluation.mjs", "agent-runner/probes/strategy-evaluation-driver.mjs", "tests/src/com/unciv/dev/AgentStrategyEvaluation.kt", "tests/src/com/unciv/dev/AgentStrategyFixtures.kt"])
  .toString().split("\0").filter(Boolean).sort();
const sourceHasher = createHash("sha256");
for (const file of sourceFiles) {
  sourceHasher.update(file + "\0");
  const path = join(sourceRoot, file);
  sourceHasher.update(existsSync(path) ? readFileSync(path) : "<deleted>");
  sourceHasher.update("\0");
}
const javaVersion = spawnSync(config.javaExecutable, ["-version"], { encoding: "utf8" });
if (javaVersion.status !== 0) throw new Error(`Java version check failed: ${javaVersion.error?.message ?? javaVersion.stderr}`);
config.sourceIdentity = {
  root: sourceRoot, head: git(["rev-parse", "HEAD"]).toString().trim(),
  status: git(["status", "--short"]).toString(), sourceSha256: sourceHasher.digest("hex"),
  meaning: "Checkout sources at launch. Archived production artifacts may come from a different commit; identify that commit with engineSourceCommit and verify runtimeClasspathHashes in the manifest.",
  sourceFileCount: sourceFiles.length, runtimeClasspathSha256: digest(classpath),
  javaVersion: `${javaVersion.stdout}${javaVersion.stderr}`.trim(),
  nodeVersion: execFileSync(config.nodeExecutable, ["--version"], { encoding: "utf8" }).trim(),
  // Provider subprocess metadata records the actual command/executable/session per invocation.
  providerExecutableOverride: process.env.UNCIV_CODEX_EXECUTABLE ?? null,
  evaluationDriverSha256: config.evaluationDriverJs ? digest(readFileSync(config.evaluationDriverJs)) : null,
};
mkdirSync(output, { recursive: true });
const frozenConfig = join(output, "evaluation-config.json");
writeFileSync(frozenConfig, JSON.stringify(config, null, 2) + "\n", { flag: "wx" });
const logPath = join(output, "jvm.log"), log = openSync(logPath, "wx");
const argv = ["-Xmx3g", `-Dunciv.data.dir=${join(output, "runtime-data")}`, "-cp", classpath,
  "com.unciv.dev.AgentStrategyEvaluation", frozenConfig];
const child = spawn(config.javaExecutable, argv, { cwd, detached: true, stdio: ["ignore", log, log],
  env: { ...process.env, UNCIV_EVALUATION_CONFIG: frozenConfig } });
const launch = { pid: child.pid, command: [config.javaExecutable, ...argv], cwd, logPath, configPath: frozenConfig,
  launchedAt: new Date().toISOString(), liveness: `ps -p ${child.pid} -o pid,ppid,etime,stat,command`,
  stopAdmissionFile: join(output, "stop-admission"), providerMetadata: join(output, "providers") };
writeFileSync(join(output, "launch.json"), JSON.stringify(launch, null, 2) + "\n", { flag: "wx" });
writeFileSync(join(output, "pid"), `${child.pid}\n`, { flag: "wx" });
child.on("error", (error) => {
  writeFileSync(join(output, "launch-error.json"), JSON.stringify({ message: error.message, at: new Date().toISOString() }) + "\n");
  console.error(error.message);
  process.exitCode = 1;
});
child.unref();
console.log(JSON.stringify(launch, null, 2));
