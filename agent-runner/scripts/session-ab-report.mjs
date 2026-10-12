#!/usr/bin/env node
/**
 * Session A/B report for one agent match's run logs.
 *
 *   node scripts/session-ab-report.mjs <userdata>/.agent-data/logs/<match-id>
 *
 * Groups engine-confirmed completed Codex turns by the session arm recorded in
 * meta.json ("rotate-N" or "resume"; runs from builds without the policy are
 * "unrecorded") and by session age. Codex reports turn.completed usage as a
 * running total for the whole session, so each resumed turn's tokens are the
 * difference from the same session's previous attempt, including failed ones.
 * Missing usage/baselines are unknown, not zero; token means show sample counts.
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const matchDir = process.argv[2];
if (!matchDir || !existsSync(matchDir)) {
  console.error("usage: node scripts/session-ab-report.mjs <match log directory>");
  process.exit(1);
}

const readJsonLines = (path) => existsSync(path) ? readFileSync(path, "utf8").split("\n").filter(Boolean).flatMap((line) => {
  try { return [JSON.parse(line)]; } catch { return []; }
}) : [];

const attempts = [];
const excluded = { "other-provider": 0, failed: 0, incomplete: 0, paused: 0, unclassified: 0, unfinished: 0 };
const nonnegativeNumber = (value) => typeof value === "number" && Number.isFinite(value) && value >= 0;
for (const name of readdirSync(matchDir)) {
  if (!name.startsWith("run-")) continue;
  const dir = join(matchDir, name);
  let meta;
  try { meta = JSON.parse(readFileSync(join(dir, "meta.json"), "utf8")); } catch { continue; }
  if (typeof meta?.provider !== "string" || !meta.provider) { excluded.unclassified++; continue; }
  if (meta.provider !== "codex") { excluded["other-provider"]++; continue; }
  const completed = Boolean(meta.endedAt) && meta.turnOutcome === "completed_turn";
  if (!meta.endedAt) excluded.unfinished++;
  else if (!completed) {
    const category = meta.turnOutcome === "failed" ? "failed" : meta.turnOutcome === "incomplete_turn" ? "incomplete"
      : meta.turnOutcome === "guard_paused" ? "paused" : "unclassified";
    excluded[category]++;
  }
  const events = readJsonLines(join(dir, "events.jsonl"));
  // An unfinished attempt may still consume more tokens after its last event.
  const usage = meta.endedAt ? events.findLast((e) => e?.type === "turn.completed")?.usage ?? null : null;
  // A request ends where the model returns tool calls; reasoning or text starts the next one.
  let requests = 1, inBatch = false, toolCalls = 0, failedCalls = 0, batches = 0;
  for (const event of events) {
    if (!event || typeof event !== "object") continue;
    const item = event.item ?? {};
    if (event.type === "item.started" && item.type === "mcp_tool_call") {
      toolCalls++;
      if (item.tool === "game.act_batch") batches++;
      if (!inBatch) { requests++; inBatch = true; }
    } else if (event.type === "item.completed" && (item.type === "reasoning" || item.type === "agent_message")) {
      inBatch = false;
    }
    if (event.type === "item.completed" && item.type === "mcp_tool_call" &&
        (item.error || item.result?.content?.some?.((c) => typeof c.text === "string" && c.text.includes('"status":"rejected"'))))
      failedCalls++;
  }
  const elapsedSeconds = (Date.parse(meta.endedAt) - Date.parse(meta.startedAt)) / 1000;
  attempts.push({
    civ: meta.civId, turn: meta.turnSerial, session: meta.sessionId, arm: meta.sessionArm ?? "unrecorded",
    age: meta.sessionAge ?? null, startedAt: Date.parse(meta.startedAt), resumed: meta.resumed, completed,
    seconds: nonnegativeNumber(meta.durationMs) ? meta.durationMs / 1000 : nonnegativeNumber(elapsedSeconds) ? elapsedSeconds : null,
    requests, toolCalls, failedCalls, batches, usage,
  });
}

// Derive usage before filtering outcomes: failed attempts can consume tokens too.
// A missing measurement breaks continuity; the next total establishes a new
// baseline instead of charging those unmeasured tokens to a successful turn.
const bySession = new Map();
for (const run of attempts.sort((a, b) => a.startedAt - b.startedAt || a.turn - b.turn)) {
  const session = typeof run.session === "string" && run.session.trim() ? run.session : null;
  const previous = session ? bySession.get(session) : null;
  const delta = (key) => {
    const current = run.usage?.[key];
    const baseline = run.resumed === false ? 0 : previous?.usage?.[key];
    return nonnegativeNumber(current) && nonnegativeNumber(baseline) && current >= baseline ? current - baseline : null;
  };
  run.tokens = { input: delta("input_tokens"), output: delta("output_tokens"), reasoning: delta("reasoning_output_tokens") };
  if (session) bySession.set(session, run);
}
const runs = attempts.filter((run) => run.completed);

const median = (values) => {
  const sorted = values.filter((v) => v != null).sort((a, b) => a - b);
  if (!sorted.length) return null;
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};
const mean = (values) => {
  const present = values.filter((v) => v != null);
  return present.length ? present.reduce((a, b) => a + b, 0) / present.length : null;
};
const fmt = (value, digits = 0) => value == null ? "-" : value.toFixed(digits);

function table(title, groups) {
  console.log(`\n${title}`);
  console.log(["group", "turns", "median s", "mean s", "requests", "tool calls", "failed", "batches", "output tok", "reason tok", "input tok"]
    .map((h, i) => i ? h.padStart(i >= 8 ? 16 : 12) : h.padEnd(20)).join(""));
  for (const [key, group] of [...groups].sort()) {
    const row = [
      group.length, median(group.map((r) => r.seconds)), mean(group.map((r) => r.seconds)),
      median(group.map((r) => r.requests)), median(group.map((r) => r.toolCalls)), mean(group.map((r) => r.failedCalls)),
      mean(group.map((r) => r.batches)),
    ];
    const tokenMeans = ["output", "reasoning", "input"].map((key) => {
      const samples = group.map((r) => r.tokens[key]).filter((value) => value != null);
      return `${fmt(mean(samples))} (n=${samples.length})`.padStart(16);
    });
    console.log(String(key).padEnd(20) + row.map((v, i) => fmt(v, i === 5 || i === 6 ? 1 : 0).padStart(12)).join("") + tokenMeans.join(""));
  }
}

const groupBy = (key) => runs.reduce((map, run) => map.set(key(run), [...(map.get(key(run)) ?? []), run]), new Map());
console.log(`${runs.length} completed Codex turns in ${matchDir}`);
console.log(`Excluded: ${Object.entries(excluded).map(([category, count]) => `${count} ${category}`).join(", ")}`);
table("By session arm (medians except failed/batches means; token means use n known per-turn samples)", groupBy((r) => r.arm));
table("By arm and session age", groupBy((r) => `${r.arm}/age ${r.age ?? "?"}`));
