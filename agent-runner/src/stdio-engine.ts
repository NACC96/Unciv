/**
 * Engine backend over the desktop↔supervisor stdio channel.
 *
 * Implements IGameService by issuing engine_request frames on the
 * supervisor's stdout and awaiting matching engine_response frames fed
 * from stdin. Timeouts fail the turn (no acknowledgement without a
 * committed receipt); shutdown/EOF rejects all pending calls.
 */
import type { EngineMethod, EngineResponseFrame } from "./engine-protocol.js";
import { ENGINE_PROTOCOL_VERSION } from "./engine-protocol.js";
import type {
  FakeError,
  FakeReceipt,
  FakeUnit,
  GameObserveResult,
  IGameService,
} from "./fake-game-mcp.js";

export class EngineDisconnected extends Error {
  constructor(message = "engine disconnected") {
    super(message);
    this.name = "EngineDisconnected";
  }
}

export class StdioEngineBackend implements IGameService {
  private seq = 0;
  private pending = new Map<
    string,
    { resolve: (r: EngineResponseFrame) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }
  >();
  private closed = false;

  constructor(
    private readonly sendLine: (line: string) => void,
    private readonly callTimeoutMs = 30000,
  ) {}

  get revision(): number {
    throw new EngineDisconnected("revision is not cached; call observe()");
  }

  get notes(): string {
    throw new EngineDisconnected("notes are not cached; call observe()/notes_get");
  }

  get notesRevision(): number {
    throw new EngineDisconnected("notes revision is not cached");
  }

  get turnEnded(): boolean {
    throw new EngineDisconnected("turn state is not cached");
  }

  /** Feeds one stdin line (engine responses only; controls go elsewhere). */
  feedLine(line: string): boolean {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(line) as Record<string, unknown>;
    } catch {
      return false;
    }
    if (parsed["protocol"] !== ENGINE_PROTOCOL_VERSION) return false;
    if (parsed["kind"] !== "engine_response" || typeof parsed["id"] !== "string") return false;
    const waiter = this.pending.get(parsed["id"] as string);
    if (!waiter) return true; // stale/duplicate response; acknowledged, ignored
    this.pending.delete(parsed["id"] as string);
    clearTimeout(waiter.timer);
    waiter.resolve(parsed as unknown as EngineResponseFrame);
    return true;
  }

  /** Rejects everything outstanding (shutdown, EOF, spawn failure). */
  disconnect(): void {
    if (this.closed) return;
    this.closed = true;
    for (const [id, waiter] of this.pending) {
      clearTimeout(waiter.timer);
      waiter.reject(new EngineDisconnected());
    }
    this.pending.clear();
  }

  private call<T>(method: EngineMethod, params: Record<string, unknown>): Promise<T> {
    if (this.closed) return Promise.reject(new EngineDisconnected());
    const id = `eng-${++this.seq}`;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`engine call ${method} timed out`));
      }, this.callTimeoutMs);
      timer.unref?.();
      this.pending.set(id, {
        resolve: (frame) => {
          if (!frame.ok) {
            const err = (frame.error ?? { code: "INTERNAL_ERROR", message: "engine error" }) as {
              code: string;
              message: string;
            };
            const typed = new Error(err.message) as Error & { code?: string };
            typed.code = err.code;
            reject(typed);
            return;
          }
          resolve(frame.result as T);
        },
        reject,
        timer,
      });
      this.sendLine(JSON.stringify({ protocol: 1, kind: "engine_request", id, method, params }));
    });
  }

  observe(): Promise<GameObserveResult> {
    return this.call("observe", {});
  }

  async inspect(unitId: number): Promise<FakeUnit | FakeError> {
    return this.call("inspect", { unitId });
  }

  async act(envelope: {
    protocolVersion: number;
    actionId: string;
    expectedRevision: number;
    type: string;
    args: Record<string, unknown>;
  }): Promise<FakeReceipt | FakeError> {
    return this.call("act", envelope as unknown as Record<string, unknown>);
  }

  async actionResult(actionId: string): Promise<FakeReceipt | { status: "pending" | "rejected"; actionId: string }> {
    return this.call("action_result", { actionId });
  }

  async updateNotes(notes: string, expectedNotesRevision: number): Promise<{ notesRevision: number } | FakeError> {
    return this.call("notes_put", { notes, expectedNotesRevision });
  }

  async readNotes(): Promise<{ notes: string; notesRevision: number }> {
    return this.call("notes_get", {});
  }

  async endTurn(): Promise<{ turnEnded: boolean } | FakeError> {
    return this.call("end_turn", {});
  }
}
