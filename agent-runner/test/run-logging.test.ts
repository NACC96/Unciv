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


it("redacts split native image events before raw logs, activity and stream callbacks", async () => {
  const root = mkdtempSync(join(tmpdir(), "unciv-image-logging-test-"));
  const script = join(root, "fixture.mjs");
  const secret = "PRIVATE_IMAGE_BYTES_".repeat(2000);
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
  process.stdout.write('{"type":"image","data":"'+${JSON.stringify(secret)});
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
      expect(stored).not.toContain("PRIVATE_IMAGE_BYTES_"); expect(stored.length).toBeLessThan(5000);
    }
    expect(streamed).toHaveLength(2); expect(streamed.join("\n")).not.toContain("PRIVATE_IMAGE_BYTES_");
    expect(readFileSync(join(runDir, "events.jsonl"), "utf8")).toContain("image metadata");
    expect(readFileSync(join(runDir, "events.jsonl"), "utf8")).toContain("unparseable image event omitted");
    expect(child.stderrText()).not.toContain("PRIVATE_IMAGE_BYTES_");
  } finally { await exited; rmSync(root, { recursive: true, force: true }); }
}, 10000);
