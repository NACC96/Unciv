#!/usr/bin/env node
/**
 * Opt-in copied-match driver. Uses the configured arm's production Supervisor,
 * prompt, MCP and provider adapter. Unlike the desktop daemon, waits for the
 * entire provider process (including normal Codex compaction) to exit before
 * releasing a turn. No cancellation, compaction timeout or provider kill path.
 * The parent harness governs admission; stdin shutdown/EOF drains naturally.
 */
import { mkdirSync, readFileSync } from "node:fs";
import { dirname, isAbsolute, join } from "node:path";
import { pathToFileURL } from "node:url";

const configPath = process.env.UNCIV_EVALUATION_CONFIG;
if (!configPath || !isAbsolute(configPath)) throw new Error("UNCIV_EVALUATION_CONFIG must name the frozen absolute evaluation config");
const config = JSON.parse(readFileSync(configPath, "utf8"));
if (config.sessionPolicy?.mode !== "forked-persistent") throw new Error("Evaluation driver requires explicit forked-persistent sessions");
if (!isAbsolute(config.daemonJs) || !isAbsolute(config.outputDirectory)) throw new Error("Evaluation paths must be absolute");
const sources = config.sessionPolicy.forkFromSessionIds;
if (!sources || typeof sources !== "object" || Array.isArray(sources) ||
    !Object.keys(sources).length || Object.values(sources).some(id => typeof id !== "string" || !id.trim()))
  throw new Error("forkFromSessionIds must map every evaluated civilization to its source history");
const sourceIds = new Set(Object.values(sources));
const sessions = new Map();
const dist = dirname(config.daemonJs);
const moduleFromArm = name => import(pathToFileURL(join(dist, name)).href);
const [{ Supervisor, NodeRunner }, { StdioEngineBackend }, { buildSpawnForProvider, MAX_TURN_SUMMARY_CHARS },
  { parseCodexStream }, { parseInbound, encodeFrame }, { STRATEGY_POLICY_VERSION }] = await Promise.all([
  moduleFromArm("supervisor.js"), moduleFromArm("stdio-engine.js"), moduleFromArm("daemon.js"),
  moduleFromArm("provider-events.js"), moduleFromArm("engine-protocol.js"), moduleFromArm("strategy-policy.js"),
]);
const scratch = join(config.outputDirectory, "provider-scratch");
mkdirSync(scratch, { recursive: true, mode: 0o700 });
let actor = "";
const backend = new StdioEngineBackend(line => process.stdout.write(line + "\n"), 30000, () => actor);
let activeTurn = null;
let shuttingDown = false;
const emit = (control, event, payload) => process.stdout.write(encodeFrame({ protocol: 1, kind: "turn_event", runId: control.runId, event, payload }) + "\n");

function admittedSession(control) {
  const civ = control.binding.civId;
  const providerConfig = control.providerConfig;
  const source = sources[civ];
  const owned = sessions.get(civ);
  if (control.provider !== "codex" || !source) throw new Error(`No Codex source session configured for ${civ}`);
  if (providerConfig.compactAfterTurn !== true) throw new Error("Persistent evaluation requires production post-turn compaction");
  if (sourceIds.has(providerConfig.resumeId)) throw new Error("A live source session may only be forked, never resumed");
  if (owned) {
    if (providerConfig.resumeId !== owned || providerConfig.forkFromSessionId)
      throw new Error(`Only ${civ}'s new evaluation session may be resumed`);
  } else if (providerConfig.resumeId || providerConfig.forkFromSessionId !== source) {
    throw new Error(`The first ${civ} invocation must fork its configured source history`);
  }
  return { civ, owned, providerConfig };
}

async function runTurn(control) {
  try {
    const { civ, owned, providerConfig } = admittedSession(control);
    actor = civ;
    emit(control, "run_started", { provider: control.provider, civ, strategyPolicyVersion: STRATEGY_POLICY_VERSION });
    const supervisor = new Supervisor((prompt, resumeId, serverUrl, bearer, extras) => {
      const built = buildSpawnForProvider("codex", prompt, resumeId, serverUrl, bearer, providerConfig, scratch, extras);
      return { ...built, spec: { ...built.spec, context: { ...control.binding, turnSerial: control.context.turnSerial,
        provider: "codex", model: providerConfig.model, reasoning: providerConfig.reasoning,
        resumed: Boolean(resumeId),
        ...(providerConfig.forkFromSessionId ? { forkedFromSessionId: providerConfig.forkFromSessionId } : {}),
        ...(control.context.session ? { sessionArm: control.context.session.arm, sessionAge: control.context.session.age } : {}) } } };
    }, parseCodexStream, new NodeRunner(), Number.POSITIVE_INFINITY);
    const outcome = await supervisor.runLeaderTurn({
      engine: backend, binding: control.binding, profile: control.profile, context: control.context,
      guidance: control.guidance, resumeId: providerConfig.resumeId, cwd: scratch,
      includeStaticInstructions: false, attachMapImage: true,
      releaseAfterEndTurn: false,
    });
    const sessionId = outcome.run.sessionId;
    if (!sessionId || sourceIds.has(sessionId) || owned && sessionId !== owned ||
        [...sessions].some(([otherCiv, id]) => otherCiv !== civ && id === sessionId))
      throw new Error(`Provider returned an invalid or unowned evaluation session for ${civ}`);
    sessions.set(civ, sessionId);
    emit(control, "session_bound", { sessionId });
    emit(control, outcome.status === "failed" || outcome.status === "cancelled" ? "run_failed" : "run_completed", {
      status: outcome.status, revision: outcome.revision, turnEnded: outcome.turnEnded, detail: outcome.detail,
      toolCalls: outcome.run.toolCalls.map(call => call.tool),
      ...(outcome.run.finalText?.trim() ? { summary: outcome.run.finalText.trim().slice(0, MAX_TURN_SUMMARY_CHARS) } : {}),
    });
  } catch (error) {
    emit(control, "run_failed", { reason: error instanceof Error ? error.message : String(error) });
  }
}

function finish() {
  backend.disconnect();
  process.exit(0);
}
function requestShutdown() {
  if (shuttingDown) return;
  shuttingDown = true;
  if (activeTurn) void activeTurn.then(finish, finish);
  else finish();
}
function handleLine(line) {
  const frame = parseInbound(line);
  if (!frame) return;
  if (frame.kind === "engine_response") { backend.feedLine(line); return; }
  if (frame.op === "shutdown") { requestShutdown(); return; }
  if (frame.op !== "run_turn") return;
  if (shuttingDown || activeTurn) {
    emit(frame, "run_failed", { reason: shuttingDown ? "evaluation shutting down" : "turn already in flight" });
    return;
  }
  activeTurn = runTurn(frame).finally(() => { activeTurn = null; });
}
process.on("SIGTERM", requestShutdown);
process.stdin.setEncoding("utf8");
let buffer = "";
process.stdin.on("data", chunk => {
  buffer += chunk;
  const lines = buffer.split("\n"); buffer = lines.pop() ?? "";
  for (const line of lines) handleLine(line);
});
process.stdin.on("end", requestShutdown);
process.stdout.write(JSON.stringify({ protocol: 1, kind: "ready" }) + "\n");
