import { expect, it } from "vitest";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { NodeRunner } from "../src/supervisor.js";

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
