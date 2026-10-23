import { expect, it, vi } from "vitest";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { NodeRunner, Supervisor } from "../src/supervisor.js";
import { FakeGameService } from "../src/fake-game-mcp.js";

it("persists each run's monotonic duration on successful and failed exits despite wall-clock shifts", async () => {
  const root = mkdtempSync(join(tmpdir(), "unciv-run-duration-test-"));
  const script = join(root, "provider.mjs");
  writeFileSync(script, `
import { existsSync } from 'node:fs';
process.stdout.write(JSON.stringify({type:'thread.started',thread_id:'duration-session'})+'\\n');
process.stdout.write(JSON.stringify({type:'image',data:'PRIVATE_DURATION_IMAGE_BYTES'})+'\\n');
const timer=setInterval(()=>{if(existsSync(process.env.RELEASE_FILE)){clearInterval(timer);process.exit(Number(process.env.EXIT_CODE));}},10);
`);
  const logRoot = join(root, "logs");
  let monotonicMillis = 5000;
  const runner = new NodeRunner(logRoot, () => monotonicMillis);
  vi.useFakeTimers({ toFake: ["Date"] });
  try {
    for (const fixture of [
      { runId: "completed-run", code: 0, durationMs: 3723456, readable: "1:02:03", status: "exited" },
      { runId: "failed-run", code: 7, durationMs: 12500, readable: "00:12", status: "failed" },
      { runId: "instant-run", code: 0, durationMs: 0, readable: "00:00", status: "exited" },
    ]) {
      vi.setSystemTime(new Date("2026-10-11T12:00:00Z"));
      const release = join(root, fixture.runId);
      const child = runner.spawn({ executable: process.execPath, argv: [script, "--bearer", "private-bearer"], cwd: root,
        context: { matchId: "timed-match", civId: "Rome", runId: fixture.runId, turnSerial: 8, provider: "fixture" } },
        { ...process.env, RELEASE_FILE: release, EXIT_CODE: String(fixture.code) });
      const exited = child.wait();
      try {
        await new Promise<void>(resolve => child.stdoutLines(() => resolve()));
        const runDir = readdirSync(logRoot).map(name => join(logRoot, name)).find(dir =>
          JSON.parse(readFileSync(join(dir, "meta.json"), "utf8")).runId === fixture.runId)!;
        const activeMeta = JSON.parse(readFileSync(join(runDir, "meta.json"), "utf8"));
        expect(activeMeta.durationMs).toBeUndefined();
        expect(readFileSync(join(runDir, "pid"), "utf8").trim()).toBe(String(child.pid));
        monotonicMillis += fixture.durationMs;
        vi.setSystemTime(new Date("2026-10-10T12:00:00Z"));
        writeFileSync(release, "go");
        expect((await exited).code).toBe(fixture.code);
        const meta = JSON.parse(readFileSync(join(runDir, "meta.json"), "utf8"));
        expect.soft(meta).toMatchObject({ runId: fixture.runId, civId: "Rome", matchId: "timed-match", turnSerial: 8,
          sessionId: "duration-session", exitCode: fixture.code, signal: null, durationMs: fixture.durationMs });
        expect(meta.endedAt < meta.startedAt).toBe(true);
        expect.soft(Number.isFinite(meta.durationMs)).toBe(true);
        const storedActivity = readFileSync(join(runDir, "activity.jsonl"), "utf8");
        const final = storedActivity.trim().split("\n").map(line => JSON.parse(line)).filter(entry => entry.kind === "run");
        expect.soft(final).toHaveLength(1);
        if (final[0]) {
          expect(final[0]).toMatchObject({ id: "run-end", status: fixture.status, durationMs: fixture.durationMs, timestamp: meta.endedAt });
          expect(final[0].title).toContain(fixture.readable);
          expect(final[0].summary).toContain(fixture.readable);
          expect(JSON.parse(final[0].text)).toEqual(meta);
        }
        for (const name of ["meta.json", "events.jsonl", "activity.jsonl", "stderr.log"]) {
          const stored = readFileSync(join(runDir, name), "utf8");
          expect(stored).not.toContain("private-bearer");
          expect(stored).not.toContain("PRIVATE_DURATION_IMAGE_BYTES");
        }
      } finally {
        writeFileSync(release, "go");
        await exited;
      }
      monotonicMillis += 60000; // Idle time between invocations must not carry into the next duration.
    }
  } finally {
    vi.useRealTimers();
    rmSync(root, { recursive: true, force: true });
  }
}, 10000);

it.each([
  { code: 0, commitEndTurn: false, outcome: "incomplete_turn", title: "Provider incomplete" },
  { code: 0, commitEndTurn: true, outcome: "completed_turn", title: "Provider completed" },
  { code: 7, commitEndTurn: false, outcome: "failed", title: "Provider failed" },
])("records authoritative turn outcome for exit $code and end-turn $commitEndTurn", async fixture => {
  const root = mkdtempSync(join(tmpdir(), "unciv-turn-outcome-log-test-"));
  const logRoot = join(root, "logs");
  const engine = new FakeGameService();
  const observe = engine.observe.bind(engine);
  let observationCount = 0;
  engine.observe = async () => {
    if (++observationCount === 2 && fixture.commitEndTurn) await engine.endTurn();
    return { ...await observe(), gameTurn: 124 };
  };
  const supervisor = new Supervisor(() => ({ spec: { executable: process.execPath,
    argv: ["-e", `process.exit(${fixture.code})`], cwd: root }, env: {} }),
    () => ({ provider: "scripted", sessionId: null, model: null, toolCalls: [], finalText: null,
      usage: null, completed: true, errorText: null, advertisedTools: null }), new NodeRunner(logRoot));
  try {
    const outcome = await supervisor.runLeaderTurn({ engine,
      binding: { matchId: "m", timelineId: "t", civId: "Rome", runId: "outcome", authorityEpoch: 1 },
      profile: { displayName: "Augustus", personality: "", modelLabel: "fixture" },
      context: { rulesetName: "Vanilla", protocolVersion: 1, civId: "Rome", turnSerial: 3 }, guidance: [], cwd: root });
    expect(outcome.status).toBe(fixture.outcome);
    const runDir = join(logRoot, readdirSync(logRoot)[0]!);
    const final = readFileSync(join(runDir, "activity.jsonl"), "utf8").trim().split("\n")
      .map(line => JSON.parse(line)).filter(entry => entry.id === "run-end").at(-1);
    expect(final.status).toBe(fixture.outcome);
    expect(final.title).toContain(fixture.title);
    expect(final.durationMs).toBeGreaterThanOrEqual(0);
    expect(final.summary).toContain(outcome.detail);
    const identity = { matchId: "m", timelineId: "t", civId: "Rome", turnSerial: 3, gameTurn: 124,
      strategyPolicyVersion: "victory-progress-v3" };
    expect(JSON.parse(readFileSync(join(runDir, "meta.json"), "utf8"))).toMatchObject({ ...identity, turnOutcome: fixture.outcome });
    expect(JSON.parse(final.text)).toMatchObject(identity);
    const started = readFileSync(join(runDir, "activity.jsonl"), "utf8").trim().split("\n")
      .map(line => JSON.parse(line)).find(entry => entry.id === "run-start");
    expect(JSON.parse(started.text)).toMatchObject(identity);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

it("persists process identity and streamed events before the provider exits", async () => {
  const root = mkdtempSync(join(tmpdir(), "unciv-run-logging-test-"));
  const release = join(root, "release");
  const script = join(root, "provider.mjs");
  writeFileSync(script, `
import { existsSync } from 'node:fs';
process.stdout.write(JSON.stringify({type:'thread.started',thread_id:'session-test'})+'\\n');
process.stderr.write('provider diagnostic\\n');
const timer=setInterval(()=>{if(existsSync(${JSON.stringify(release)})){clearInterval(timer);process.exit(0);}},10);
`);
  const logRoot = join(root, "logs");
  const child = new NodeRunner(logRoot).spawn(
    { executable: process.execPath, argv: [script], cwd: root, context: { matchId: "match-test", civId: "Rome", turnSerial: 3, runId: "turn-test", provider: "codex", model: "gpt-6-luna", reasoning: "xhigh" } }, process.env,
  );
  const exited = child.wait();
  try {
    await new Promise<void>((resolve) => child.stdoutLines(() => resolve()));
    const runDir = join(logRoot, readdirSync(logRoot)[0]!);
    const meta = JSON.parse(readFileSync(join(runDir, "meta.json"), "utf8"));
    expect(meta.pid).toBe(child.pid);
    expect(meta).toMatchObject({ matchId: "match-test", civId: "Rome", turnSerial: 3, provider: "codex", model: "gpt-6-luna", reasoning: "xhigh" });
    expect(readFileSync(join(runDir, "activity.jsonl"), "utf8")).toContain("thread.started");
    expect(meta.cwd).toBe(root);
    expect(meta.command).toEqual([process.execPath, script]);
    expect(meta.sessionId).toBe("session-test");
    expect(readFileSync(join(runDir, "events.jsonl"), "utf8")).toContain('"thread_id":"session-test"');
    expect(readFileSync(join(runDir, "pid"), "utf8").trim()).toBe(String(child.pid));
    writeFileSync(release, "go");
    expect((await exited).code).toBe(0);
  } finally {
    writeFileSync(release, "go");
    await exited;
    rmSync(root, { recursive: true, force: true });
  }
}, 10000);


it("redacts split native image events before raw logs, activity and stream callbacks", async () => {
  const root = mkdtempSync(join(tmpdir(), "unciv-image-logging-test-"));
  const script = join(root, "fixture.mjs");
  const secret = Buffer.from("PRIVATE_IMAGE_BYTES_".repeat(2000)).toString("base64");
  const event = JSON.stringify({ type: "item.completed", item: { id: "image-read", type: "mcp_tool_call",
    tool: "game.message_image", status: "completed", result: { content: [{ type: "text", text: "image metadata" },
      { type: "image", mimeType: "image/png", data: secret }] } } });
  const nested = JSON.stringify({ type: "tool_use", part: { id: "image-nested", tool: "game.message_image",
    state: { status: "completed", output: JSON.stringify({ content: [{ type: "image", source: { type: "base64", media_type: "image/png", data: secret } }] }) } } });
  writeFileSync(script, `
const event = ${JSON.stringify(event)}, nested = ${JSON.stringify(nested)};
process.stdout.write(event.slice(0,100));
process.stderr.write(nested.slice(0,100));
setTimeout(() => {
  process.stdout.write(event.slice(100)+'\\n'+nested+'\\n');
  process.stderr.write(nested.slice(100)+'\\n');
  process.stdout.write('{"data":"'+${JSON.stringify(secret)});
  process.stderr.write('{"data":"'+${JSON.stringify(secret)});
}, 20);
`);
  const logRoot = join(root, "logs"), streamed: string[] = [];
  const child = new NodeRunner(logRoot).spawn({ executable: process.execPath, argv: [script], cwd: root }, process.env);
  child.stdoutLines(line => streamed.push(line));
  const exited = child.wait();
  try {
    expect((await exited).code).toBe(0);
    const runDir = join(logRoot, readdirSync(logRoot)[0]!);
    for (const name of ["events.jsonl", "activity.jsonl", "stderr.log"]) {
      const stored = readFileSync(join(runDir, name), "utf8");
      expect(stored).not.toContain(secret); expect(stored.length).toBeLessThan(5000);
    }
    expect(streamed).toHaveLength(2); expect(streamed.join("\n")).not.toContain(secret);
    expect(readFileSync(join(runDir, "events.jsonl"), "utf8")).toContain("image metadata");
    expect(readFileSync(join(runDir, "events.jsonl"), "utf8")).toContain("unparseable image event omitted");
    expect(child.stderrText()).not.toContain(secret);
  } finally { await exited; rmSync(root, { recursive: true, force: true }); }
}, 10000);
