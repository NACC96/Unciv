#!/usr/bin/env node
/**
 * Session A/B report for one agent match's run logs.
 *
 *   node scripts/session-ab-report.mjs <userdata>/.agent-data/logs/<match-id>
 *
 * Groups completed Codex runs by the session arm the desktop recorded in
 * meta.json ("rotate-N" or "resume"; runs from builds without the policy are
 * "unrecorded") and by session age. Codex reports turn.completed usage as a
 * running total for the whole session, so each resumed turn's tokens are the
 * difference from the same session's previous turn.
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

const runs = [];
for (const name of readdirSync(matchDir)) {
  if (!name.startsWith("run-")) continue;
  const dir = join(matchDir, name);
  let meta;
  try { meta = JSON.parse(readFileSync(join(dir, "meta.json"), "utf8")); } catch { continue; }
  if (!meta.endedAt) continue;
  const events = readJsonLines(join(dir, "events.jsonl"));
  const usage = events.findLast((e) => e.type === "turn.completed")?.usage ?? null;
  // A request ends where the model returns tool calls; reasoning or text starts the next one.
  let requests = 1, inBatch = false, toolCalls = 0, failedCalls = 0, batches = 0;
  for (const event of events) {
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
  runs.push({
    civ: meta.civId, turn: meta.turnSerial, session: meta.sessionId, arm: meta.sessionArm ?? "unrecorded",
    age: meta.sessionAge ?? null, seconds: (Date.parse(meta.endedAt) - Date.parse(meta.startedAt)) / 1000,
    requests, toolCalls, failedCalls, batches, usage,
  });
}

// Per-turn usage: difference cumulative session totals.
const bySession = new Map();
for (const run of runs.sort((a, b) => a.turn - b.turn)) {
  const previous = bySession.get(run.session);
  const delta = (key) => run.usage ? (run.usage[key] ?? 0) - (previous?.usage?.[key] ?? 0) : null;
  run.tokens = { input: delta("input_tokens"), output: delta("output_tokens"), reasoning: delta("reasoning_output_tokens") };
  if (run.usage) bySession.set(run.session, run);
}

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
    .map((h, i) => i ? h.padStart(12) : h.padEnd(20)).join(""));
  for (const [key, group] of [...groups].sort()) {
    const row = [
      group.length, median(group.map((r) => r.seconds)), mean(group.map((r) => r.seconds)),
      median(group.map((r) => r.requests)), median(group.map((r) => r.toolCalls)), mean(group.map((r) => r.failedCalls)),
      mean(group.map((r) => r.batches)), mean(group.map((r) => r.tokens.output)), mean(group.map((r) => r.tokens.reasoning)),
      mean(group.map((r) => r.tokens.input)),
    ];
    console.log(String(key).padEnd(20) + row.map((v, i) => fmt(v, i === 5 || i === 6 ? 1 : 0).padStart(12)).join(""));
  }
}

const groupBy = (key) => runs.reduce((map, run) => map.set(key(run), [...(map.get(key(run)) ?? []), run]), new Map());
console.log(`${runs.length} completed runs in ${matchDir}`);
table("By session arm (medians except where noted; failed/batches/tokens are means per turn)", groupBy((r) => r.arm));
table("By arm and session age", groupBy((r) => `${r.arm}/age ${r.age ?? "?"}`));
