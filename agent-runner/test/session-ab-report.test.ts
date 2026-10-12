import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";

const reportScript = fileURLToPath(new URL("../scripts/session-ab-report.mjs", import.meta.url));
const fixtures: string[] = [];
function fixture() {
  const dir = mkdtempSync(join(tmpdir(), "unciv-ab-report-"));
  fixtures.push(dir);
  let sequence = 0;
  return {
    run(meta: Record<string, unknown> = {}, usage?: Record<string, unknown>) {
      const index = sequence++;
      const runDir = join(dir, `run-${index}`);
      mkdirSync(runDir);
      writeFileSync(join(runDir, "meta.json"), JSON.stringify({
        provider: "codex", turnOutcome: "completed_turn", civId: "Rome", turnSerial: index,
        sessionId: `session-${index}`, resumed: false, sessionArm: "rotate-1", sessionAge: 1,
        startedAt: new Date(index * 100_000).toISOString(), endedAt: new Date(index * 100_000 + 10_000).toISOString(),
        ...meta,
      }));
      writeFileSync(join(runDir, "events.jsonl"), usage ? JSON.stringify({ type: "turn.completed", usage }) + "\n" : "");
    },
    report: () => execFileSync(process.execPath, [reportScript, dir], { encoding: "utf8" }),
  };
}
afterEach(() => { for (const dir of fixtures.splice(0)) rmSync(dir, { recursive: true, force: true }); });

describe("session A/B report", () => {
  it("compares only Codex runs with engine-confirmed completed turns and reports exclusions", () => {
    const f = fixture();
    f.run(); // ten-second completed Codex turn
    f.run({ provider: "claude", durationMs: 100_000 });
    f.run({ turnOutcome: "failed", durationMs: 1_000 });
    f.run({ turnOutcome: "incomplete_turn", exitCode: 0 });
    f.run({ turnOutcome: "guard_paused" });
    f.run({ turnOutcome: undefined, exitCode: 0 });
    f.run({ endedAt: undefined, turnOutcome: undefined });
    const output = f.report();
    expect(output).toContain("1 completed Codex turns");
    expect(output).toContain("Excluded: 1 other-provider, 1 failed, 1 incomplete, 1 paused, 1 unclassified, 1 unfinished");
    expect(output).toMatch(/^rotate-1\s+1\s+10\s+10\s/m);
  });

  it("updates cumulative usage baselines for excluded failed attempts before aggregating successes", () => {
    const f = fixture();
    const shared = { sessionId: "shared", sessionArm: "resume" };
    f.run(shared, { input_tokens: 100, output_tokens: 20, reasoning_output_tokens: 10 });
    f.run({ ...shared, resumed: true, turnOutcome: "failed" }, { input_tokens: 160, output_tokens: 35, reasoning_output_tokens: 15 });
    f.run({ ...shared, resumed: true }, { input_tokens: 200, output_tokens: 45, reasoning_output_tokens: 19 });
    const output = f.report();
    // Completed turns consume 100 + 40 input, 20 + 10 output and 10 + 4 reasoning tokens.
    expect(output).toMatch(/^resume\s+2\s+10\s+10\s+1\s+0\s+0\.0\s+0\.0\s+15 \(n=2\)\s+7 \(n=2\)\s+70 \(n=2\)/m);
  });

  it("does not merge missing session IDs or assume the first observed resumed usage starts at zero", () => {
    const f = fixture();
    f.run({ resumed: true, sessionId: undefined }, { input_tokens: 100, output_tokens: 20 });
    f.run({ resumed: true, sessionId: undefined }, { input_tokens: 150, output_tokens: 30 });
    f.run({ resumed: true, sessionId: "existing" }, { input_tokens: 500, output_tokens: 100 });
    f.run({ resumed: true, sessionId: "existing" }, { input_tokens: 530, output_tokens: 106 });
    const output = f.report();
    expect(output).toMatch(/^rotate-1\s+4\s+10\s+10\s+1\s+0\s+0\.0\s+0\.0\s+6 \(n=1\)\s+- \(n=0\)\s+30 \(n=1\)/m);
  });

  it("marks usage after an unmeasured attempt or counter reset unknown, then recovers the baseline", () => {
    const f = fixture();
    const shared = { sessionId: "shared", resumed: true };
    f.run({ ...shared, resumed: false, turnOutcome: "failed" }, { input_tokens: 100, output_tokens: 20 });
    f.run({ ...shared, turnOutcome: "failed" });
    f.run(shared, { input_tokens: 150, output_tokens: 30 }); // includes unmeasured failed attempt
    f.run(shared, { input_tokens: 10, output_tokens: 2 }); // counter reset
    f.run(shared, { input_tokens: 40, output_tokens: 8 });
    const output = f.report();
    expect(output).toMatch(/^rotate-1\s+3\s+10\s+10\s+1\s+0\s+0\.0\s+0\.0\s+6 \(n=1\)\s+- \(n=0\)\s+30 \(n=1\)/m);
  });

  it("orders same-turn attempts by start time and excludes non-Codex usage from their baseline", () => {
    const f = fixture();
    const shared = { sessionId: "shared", turnSerial: 1 };
    f.run({ ...shared, resumed: true, startedAt: new Date(200_000).toISOString(), endedAt: new Date(210_000).toISOString() },
      { input_tokens: 160, output_tokens: 30 });
    f.run({ ...shared, turnOutcome: "failed", startedAt: new Date(0).toISOString(), endedAt: new Date(10_000).toISOString() },
      { input_tokens: 100, output_tokens: 20 });
    f.run({ ...shared, provider: "claude", startedAt: new Date(100_000).toISOString(), endedAt: new Date(110_000).toISOString() },
      { input_tokens: 999, output_tokens: 999 });
    expect(f.report()).toMatch(/^rotate-1\s+1\s+10\s+10\s+1\s+0\s+0\.0\s+0\.0\s+10 \(n=1\)\s+- \(n=0\)\s+60 \(n=1\)/m);
  });

  it("does not treat unknown legacy baselines or interrupted attempts as zero cost", () => {
    const f = fixture();
    f.run({ provider: undefined });
    f.run({ sessionId: "shared", resumed: undefined }, { input_tokens: 100, output_tokens: 20 });
    f.run({ sessionId: "shared", resumed: true, endedAt: undefined }, { input_tokens: 110, output_tokens: 21 });
    f.run({ sessionId: "shared", resumed: true }, { input_tokens: 150, output_tokens: 30 });
    const output = f.report();
    expect(output).toContain("0 other-provider, 0 failed, 0 incomplete, 0 paused, 1 unclassified, 1 unfinished");
    expect(output).toMatch(/^rotate-1\s+2\s+10\s+10\s+1\s+0\s+0\.0\s+0\.0\s+- \(n=0\)\s+- \(n=0\)\s+- \(n=0\)/m);
  });
});
