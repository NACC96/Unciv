import { afterEach, expect, it, vi } from "vitest";
import { main } from "../src/daemon.js";

const boundary = vi.hoisted(() => ({ env: undefined as NodeJS.ProcessEnv | undefined }));
vi.mock("../src/supervisor.js", () => ({ Supervisor: class {
  constructor(private build: (...args: any[]) => any) {}
  async runLeaderTurn(args: any) {
    const built = this.build("prompt", undefined, "http://127.0.0.1:1234/mcp", "fresh-invocation-token");
    boundary.env = { ...process.env, ...built.env, ...args.extraEnv };
    return { status: "incomplete_turn", revision: 0, turnEnded: false, detail: "mocked", run: { sessionId: null, toolCalls: [] } };
  }
} }));
vi.mock("../src/stdio-engine.js", () => ({ StdioEngineBackend: class { disconnect() {} } }));
afterEach(() => vi.restoreAllMocks());

it("does not replace the minted Codex bearer with a daemon placeholder", async () => {
  const stdin = process.stdin;
  const oldData = stdin.listeners("data");
  const oldEnd = stdin.listeners("end");
  const oldTerm = process.listeners("SIGTERM");
  vi.spyOn(stdin, "setEncoding").mockReturnValue(stdin);
  vi.spyOn(process.stdout, "write").mockReturnValue(true);
  try {
    await main();
    const receive = stdin.listeners("data").find(listener => !oldData.includes(listener))!;
    receive(JSON.stringify({ protocol: 1, kind: "control", op: "run_turn", runId: "r", provider: "codex", providerConfig: {},
      binding: { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 },
      profile: { displayName: "Ada", personality: "", modelLabel: "m" },
      context: { rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "Rome", turnSerial: 1 }, guidance: [] }) + "\n");
    await Promise.resolve(); await Promise.resolve();
    expect(boundary.env?.GAME_MCP_BEARER).toBe("fresh-invocation-token");
  } finally {
    for (const listener of stdin.listeners("data")) if (!oldData.includes(listener)) stdin.removeListener("data", listener);
    for (const listener of stdin.listeners("end")) if (!oldEnd.includes(listener)) stdin.removeListener("end", listener);
    for (const listener of process.listeners("SIGTERM")) if (!oldTerm.includes(listener)) process.removeListener("SIGTERM", listener);
  }
});
