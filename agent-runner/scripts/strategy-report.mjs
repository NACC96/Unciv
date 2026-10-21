#!/usr/bin/env node
/** Read-only strategy evidence aggregation. Usage: node scripts/strategy-report.mjs RUN [BASELINE_RUN]
 * JSON output includes the counting method, exclusions and evidence IDs. A bounded run without
 * engine victory data is inconclusive for win rate. Differences are descriptive, never a pass
 * for strategic quality, which still requires inspecting the milestones and raw snapshots.
 */
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";

const number = (v) => typeof v === "number" && Number.isFinite(v) && v >= 0;
const median = (values) => {
  const sorted = values.filter(number).sort((a, b) => a - b);
  return sorted.length ? (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.floor(sorted.length / 2)]) / 2 : null;
};
const object = (value) => value && typeof value === "object" && !Array.isArray(value);
function json(path) { try { return JSON.parse(readFileSync(path, "utf8")); } catch { return null; } }
function lines(path, errors) {
  if (!existsSync(path)) return [];
  return readFileSync(path, "utf8").split("\n").flatMap((line, index) => {
    if (!line.trim()) return [];
    try { return [JSON.parse(line)]; } catch { errors.push(`${path}:${index + 1}`); return []; }
  });
}
// Ignore only transport clocks/revisions; returned health, position, orders, path options,
// visible threats and decisions remain significant. No semantic claim is made from args alone.
const volatile = new Set(["revision", "stateRevision", "turnSerial", "gameRound", "generatedAt", "protocolVersion"]);
function stable(value, omitVolatile = false) {
  if (Array.isArray(value)) return value.map((v) => stable(v, omitVolatile));
  if (!object(value)) return value;
  return Object.fromEntries(Object.keys(value).sort().filter((k) => !omitVolatile || !volatile.has(k)).map((k) => [k, stable(value[k], omitVolatile)]));
}
const fingerprint = (value) => JSON.stringify(stable(value, true));

function providers(directory, errors) {
  const root = existsSync(join(directory, "providers")) ? join(directory, "providers") : directory;
  return readdirSync(root, { withFileTypes: true }).filter((d) => d.isDirectory()).flatMap((d) => {
    const path = join(root, d.name), meta = json(join(path, "meta.json"));
    return meta ? [{ path, meta, events: lines(join(path, "events.jsonl"), errors) }] : [];
  }).sort((a, b) => Date.parse(a.meta.startedAt ?? 0) - Date.parse(b.meta.startedAt ?? 0));
}

function legacyCalls(attempts) {
  const calls = [], seen = new Set();
  for (const { path, meta, events } of attempts) for (const event of events) {
    // Deliberately limited to complete, nontruncated Codex MCP results. Other formats are unknown.
    const item = event.item;
    if (event.type !== "item.completed" || item?.type !== "mcp_tool_call" || !item.id) continue;
    const key = `${path}/${item.id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    let result = item.result?.structuredContent;
    if (!result) for (const content of item.result?.content ?? []) {
      if (content.type !== "text") continue;
      try { result = JSON.parse(content.text); break; } catch { /* Truncated/unknown result is not a receipt. */ }
    }
    let args = item.arguments ?? {};
    if (typeof args === "string") { try { args = JSON.parse(args); } catch { args = {}; } }
    const method = String(item.tool ?? "").replace(/^game\./, "");
    const base = { event: "engine_call_completed", runId: meta.runId ?? path, actor: meta.civId,
      timelineId: meta.timelineId ?? "legacy-unknown", method, ok: !item.error && !item.result?.isError,
      params: args, callId: key, result };
    if (method === "act_batch") {
      // The outer batch is never an action. Match receipt slots to input command slots.
      const receipts = result?.results ?? result?.receipts ?? [];
      for (const [index, receipt] of receipts.entries()) calls.push({ ...base, callId: `${key}/${index}`, method: "act",
        params: args.actions?.[index] ?? args.commands?.[index] ?? { type: receipt.type }, result: receipt });
    } else calls.push(base);
  }
  return calls;
}

function report(directory) {
  const malformed = [], events = lines(join(directory, "host-events.jsonl"), malformed);
  const attempts = providers(directory, malformed);
  const hostCalls = events.filter((e) => e.event === "engine_call_completed");
  const calls = hostCalls.length ? hostCalls : legacyCalls(attempts);
  const hostTurns = events.filter((e) => e.event === "turn_completed");
  const turns = [...new Map(hostTurns.map((e) => [e.runId, e])).values()];
  const completed = turns.filter((e) => e.status === "completed_turn" && e.endTurnsCommittedDelta > 0);
  const legacyCompleted = attempts.filter(({ meta }) => meta.endedAt && meta.turnOutcome === "completed_turn");
  const unsupportedLegacyAttempts = hostCalls.length ? 0 : attempts.filter(({ meta, events: stream }) =>
    meta.provider !== "codex" || !stream.some((e) => e.type === "item.completed" && e.item?.type === "mcp_tool_call")).length;
  const completedRunIds = new Set(hostTurns.length ? completed.map((turn) => turn.runId)
    : legacyCompleted.map(({ meta, path }) => meta.runId ?? path));
  let completedTurnOutgoingMessages = 0;
  const result = {
    directory, manifest: json(join(directory, "manifest.json")), evidenceSource: hostCalls.length ? "host" : "provider-completed-calls",
    method: "Unique completed engine calls; only applied receipts count as commands. Receipt IDs are scoped by timeline and actor. Original-capital ownership and victory come only from paired engine snapshots. Unchanged reads/orders require identical relevant returned state after excluding transport clocks/revisions.",
    turns: { completed: hostTurns.length ? completed.length : legacyCompleted.length, attempted: hostTurns.length ? turns.length : attempts.length,
      incompleteOrFailed: hostTurns.length ? turns.length - completed.length : attempts.length - legacyCompleted.length },
    commands: { applied: 0, outgoingMessages: 0, acknowledgements: 0, attacks: 0, byType: {} },
    repetition: { unchangedReads: 0, unchangedOrders: 0, unknownOrderState: 0, eligibleReads: 0, eligibleOrders: 0 },
    timing: { medianTimeToCommitMs: median(completed.map((e) => e.timeToCommitMs)),
      medianInvocationMs: median(turns.map((e) => e.durationMs)), providerMedianMs: median(attempts.map((a) => a.meta.durationMs)),
      compaction: "disabled for harness runs; unknown when unrecorded in legacy logs" },
    tokens: { completedTurns: {} },
    outcome: { winner: null, victoryType: null, victoryTurn: null, winRateVerdict: "inconclusive", originalCapitalOwnershipChanges: [], snapshots: 0 },
    exclusions: { duplicateCalls: 0, replayedReceipts: 0, failedCalls: 0, nonAppliedReceipts: 0, unknownReceipts: 0, malformedLines: malformed },
    coverage: { unsupportedLegacyAttempts, completeCommandCoverage: hostCalls.length > 0 || unsupportedLegacyAttempts === 0 },
    countedEvents: { applied: [], unchangedReads: [], unchangedOrders: [], turns: completed.map((t) => t.runId) },
    limitations: ["Diplomacy counts distinguish new messages from required acknowledgements; useful coordination and milestone quality require human assessment.",
      "Repeated-state counts are conservative equality checks, not a judgment that an action was strategically unnecessary.",
      "Missing legacy state, time-to-commit, policy identity, complete receipts, or checkpoint victory facts are unknown, never zero or inferred from narration."],
  };
  const seenCalls = new Set(), seenReceipts = new Set(), readStates = new Map(), orderStates = new Map();
  for (const call of calls) {
    const callKey = `${call.runId}/${call.callId}`;
    if (seenCalls.has(callKey)) { result.exclusions.duplicateCalls++; continue; }
    seenCalls.add(callKey);
    if (!call.ok) { result.exclusions.failedCalls++; continue; }
    if (["inspect", "inspect_city", "inspect_tile", "legal_actions", "map", "rules"].includes(call.method) && call.result != null) {
      const key = `${call.actor}/${call.method}/${fingerprint(call.params)}`;
      const state = fingerprint(call.result);
      result.repetition.eligibleReads++;
      if (readStates.get(key) === state) { result.repetition.unchangedReads++; result.countedEvents.unchangedReads.push(callKey); }
      readStates.set(key, state);
    }
    if (call.method !== "act") continue;
    const receipt = call.result;
    if (!receipt?.status) { result.exclusions.unknownReceipts++; continue; }
    if (receipt.status !== "applied") { result.exclusions.nonAppliedReceipts++; continue; }
    const actionId = receipt.actionId ?? call.params?.actionId;
    if (!actionId) { result.exclusions.unknownReceipts++; continue; }
    const actionKey = `${call.timelineId}/${call.actor}/${actionId}/${receipt.revision ?? "unrecorded"}`;
    if (seenReceipts.has(actionKey)) { result.exclusions.replayedReceipts++; continue; }
    seenReceipts.add(actionKey);
    const type = call.params?.type ?? receipt.type ?? "unknown";
    result.commands.applied++;
    result.commands.byType[type] = (result.commands.byType[type] ?? 0) + 1;
    result.countedEvents.applied.push({ call: callKey, actionId, type });
    if (type === "diplomacy.message") {
      result.commands.outgoingMessages++;
      if (completedRunIds.has(call.runId)) completedTurnOutgoingMessages++;
    }
    if (type === "diplomacy.acknowledgeMessage") result.commands.acknowledgements++;
    if (type === "unit.attack") result.commands.attacks++;
    const args = call.params?.args;
    if (type === "unit.order" && ["SLEEP", "FORTIFY"].includes(args?.order)) {
      result.repetition.eligibleOrders++;
      if (call.relevantState == null) { result.repetition.unknownOrderState++; continue; }
      const key = `${call.actor}/${fingerprint(args)}`, state = fingerprint(call.relevantState);
      if (orderStates.get(key) === state) { result.repetition.unchangedOrders++; result.countedEvents.unchangedOrders.push(callKey); }
      orderStates.set(key, state);
    }
  }
  // Session totals may occur more than once in one stream. Last total only; derive deltas
  // across *all* attempts so a failed invocation's cost is not charged to a later success.
  const sessionUsage = new Map();
  const totals = Object.fromEntries(["input", "output", "reasoning"].map((key) => [key, { total: 0, samples: 0 }]));
  for (const { meta, events: stream } of attempts) {
    if (meta.provider !== "codex") continue;
    const usage = meta.endedAt ? stream.findLast((e) => e.type === "turn.completed")?.usage : null;
    const previous = meta.sessionId ? sessionUsage.get(meta.sessionId) : null;
    for (const [key, wire] of Object.entries({ input: "input_tokens", output: "output_tokens", reasoning: "reasoning_output_tokens" })) {
      const baseline = meta.resumed === false ? 0 : previous?.[wire], current = usage?.[wire];
      if (meta.turnOutcome === "completed_turn" && meta.endedAt && number(baseline) && number(current) && current >= baseline) {
        totals[key].total += current - baseline; totals[key].samples++;
      }
    }
    if (meta.sessionId) sessionUsage.set(meta.sessionId, usage ?? null);
  }
  result.tokens.completedTurns = Object.fromEntries(Object.entries(totals).map(([key, value]) => [key, { ...value, total: value.samples ? value.total : null }]));
  const capitals = new Map();
  for (const snapshot of events.filter((e) => e.event === "snapshot")) {
    const facts = snapshot.facts;
    if (!facts) continue;
    result.outcome.snapshots++;
    for (const capital of facts.capitals ?? []) {
      if (capital.major !== true || !capital.cityId || !capital.foundingCivId) continue;
      const previous = capitals.get(capital.cityId);
      if (previous && previous.ownerCivId !== capital.ownerCivId) result.outcome.originalCapitalOwnershipChanges.push({
        cityId: capital.cityId, foundingCivId: capital.foundingCivId, from: previous.ownerCivId, to: capital.ownerCivId, gameRound: facts.gameRound,
      });
      capitals.set(capital.cityId, capital);
    }
    const victory = facts.victoryData;
    if (typeof victory?.winningCiv === "string" && victory.winningCiv && typeof victory.victoryType === "string" && victory.victoryType) {
      Object.assign(result.outcome, { winner: victory.winningCiv, victoryType: victory.victoryType, victoryTurn: victory.victoryTurn ?? null,
        winRateVerdict: "engine-recorded victory; one scenario is not a full-match win-rate estimate" });
    }
  }
  result.commands.outgoingMessagesPerCompletedTurn = result.turns.completed && result.coverage.completeCommandCoverage
    ? completedTurnOutgoingMessages / result.turns.completed : null;
  return result;
}

const [candidatePath, baselinePath] = process.argv.slice(2);
if (!candidatePath || !existsSync(candidatePath)) {
  console.error("usage: node scripts/strategy-report.mjs RUN_DIRECTORY [BASELINE_DIRECTORY]");
  process.exitCode = 1;
} else {
  const candidate = report(resolve(candidatePath));
  if (baselinePath) {
    const baseline = report(resolve(baselinePath));
    const reduction = (before, after) => number(before) && before > 0 && number(after) ? (before - after) / before : null;
    candidate.comparison = {
      baselineDirectory: baseline.directory,
      fixtureHashesMatch: candidate.manifest?.inputSha256 && baseline.manifest?.inputSha256 ? candidate.manifest.inputSha256 === baseline.manifest.inputSha256 : null,
      unchangedReadsReduction: reduction(baseline.repetition.unchangedReads, candidate.repetition.unchangedReads),
      unchangedOrdersReduction: reduction(baseline.repetition.unchangedOrders, candidate.repetition.unchangedOrders),
      outgoingMessagesPerTurnReduction: reduction(baseline.commands.outgoingMessagesPerCompletedTurn, candidate.commands.outgoingMessagesPerCompletedTurn),
      medianTimeToCommitReduction: reduction(baseline.timing.medianTimeToCommitMs, candidate.timing.medianTimeToCommitMs),
      interpretation: "Descriptive comparison. Verify identical input, provider/model/reasoning/vision and own-turn budgets, then inspect milestone quality and repeat samples before judging targets.",
    };
  }
  console.log(JSON.stringify(candidate, null, 2));
}
