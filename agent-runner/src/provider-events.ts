/**
 * Provider stream normalization (AI_AGENT_PLAN.md §7.1).
 *
 * Adapters translate provider protocols; they own no policy. Each parser
 * reads ONE provider's `--json`/stream-json event stream and normalizes to
 * provider-namespace events: run_started, session_bound, agent_message,
 * tool_activity, usage, provider_retry, run_completed, run_failed.
 * Proven against the recorded W1 fixtures (test/provider-events.test.ts).
 */
export interface NormalizedToolCall {
  tool: string;
  input: unknown;
  completed: boolean;
  errorText: string | null;
}

export interface NormalizedUsage {
  inputTokens: number | null;
  outputTokens: number | null;
  cachedTokens: number | null;
  cost: number | null;
}

export interface NormalizedRun {
  provider: string;
  sessionId: string | null;
  model: string | null;
  toolCalls: NormalizedToolCall[];
  finalText: string | null;
  usage: NormalizedUsage | null;
  /** Process-level outcome hints; engine commits decide turn outcomes. */
  completed: boolean;
  errorText: string | null;
  /** Effective tool catalog when the stream advertises it (isolation proof). */
  advertisedTools: string[] | null;
}

const emptyRun = (provider: string): NormalizedRun => ({
  provider,
  sessionId: null,
  model: null,
  toolCalls: [],
  finalText: null,
  usage: null,
  completed: false,
  errorText: null,
  advertisedTools: null,
});

function jsonLines(stream: string): unknown[] {
  const out: unknown[] = [];
  for (const line of stream.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      out.push(JSON.parse(trimmed));
    } catch {
      // Split/malformed/truncated JSONL must not kill parsing (plan §13.2);
      // the event is dropped and counted by absence.
    }
  }
  return out;
}

/** codex exec --json */
export function parseCodexStream(stream: string): NormalizedRun {
  const run = emptyRun("codex");
  for (const raw of jsonLines(stream)) {
    const e = raw as Record<string, unknown>;
    if (typeof e["thread_id"] === "string" && !run.sessionId) run.sessionId = e["thread_id"] as string;
    const item = e["item"] as Record<string, unknown> | undefined;
    if (!item) {
      if (e["type"] === "turn.completed") {
        run.completed = true;
        const usage = e["usage"] as Record<string, unknown> | undefined;
        if (usage) {
          run.usage = {
            inputTokens: (usage["input_tokens"] as number | undefined) ?? null,
            outputTokens: (usage["output_tokens"] as number | undefined) ?? null,
            cachedTokens: (usage["cached_input_tokens"] as number | undefined) ?? null,
            cost: null, // codex JSONL carries no cost here; never fabricate
          };
        }
      }
      if (e["type"] === "error" && typeof e["message"] === "string") {
        run.errorText = ((run.errorText ?? "") + "\n" + e["message"]).trim();
      }
      continue;
    }
    if (item["type"] === "mcp_tool_call") {
      run.toolCalls.push({
        tool: item["tool"] as string,
        input: item["arguments"] ?? null,
        completed: item["status"] === "completed",
        errorText: item["error"] == null ? null : JSON.stringify(item["error"]),
      });
    }
    if (item["type"] === "agent_message" && typeof item["text"] === "string") {
      run.finalText = item["text"] as string;
    }
  }
  return run;
}

/** claude -p --output-format stream-json */
export function parseClaudeStream(stream: string): NormalizedRun {
  const run = emptyRun("claude");
  for (const raw of jsonLines(stream)) {
    const e = raw as Record<string, unknown>;
    if (typeof e["session_id"] === "string" && !run.sessionId) run.sessionId = e["session_id"] as string;
    if (e["type"] === "system" && e["subtype"] === "init") {
      if (Array.isArray(e["tools"])) run.advertisedTools = (e["tools"] as unknown[]).map(String);
      if (typeof e["model"] === "string") run.model = e["model"] as string;
    }
    if (e["type"] === "assistant") {
      const message = (e["message"] as Record<string, unknown> | undefined) ?? {};
      const content = (message["content"] as unknown[] | undefined) ?? [];
      for (const block of content) {
        const b = block as Record<string, unknown>;
        if (b["type"] === "tool_use" && typeof b["name"] === "string") {
          run.toolCalls.push({ tool: b["name"] as string, input: b["input"] ?? null, completed: true, errorText: null });
        }
        if (b["type"] === "text" && typeof b["text"] === "string") run.finalText = b["text"] as string;
      }
    }
    if (e["type"] === "result") {
      run.completed = true;
      if (typeof e["result"] === "string") run.finalText = e["result"] as string;
      const usage = e["usage"] as Record<string, unknown> | undefined;
      if (usage) {
        const input = usage["input_tokens"] as number | undefined;
        const output = usage["output_tokens"] as number | undefined;
        const cacheRead = usage["cache_read_input_tokens"] as number | undefined;
        const cacheCreate = usage["cache_creation_input_tokens"] as number | undefined;
        run.usage = {
          inputTokens: input ?? null,
          outputTokens: output ?? null,
          cachedTokens: (cacheRead ?? 0) + (cacheCreate ?? 0),
          cost: typeof e["total_cost_usd"] === "number" ? (e["total_cost_usd"] as number) : null,
        };
      }
      if (e["is_error"]) run.errorText = String(e["result"] ?? "error").slice(0, 500);
    }
  }
  return run;
}

/** opencode run --format json */
export function parseOpencodeStream(stream: string): NormalizedRun {
  const run = emptyRun("opencode");
  for (const raw of jsonLines(stream)) {
    const e = raw as Record<string, unknown>;
    if (typeof e["sessionID"] === "string" && !run.sessionId) run.sessionId = e["sessionID"] as string;
    const part = e["part"] as Record<string, unknown> | undefined;
    if (!part) continue;
    if (e["type"] === "tool_use" && typeof part["tool"] === "string") {
      const state = (part["state"] as Record<string, unknown> | undefined) ?? {};
      run.toolCalls.push({
        tool: part["tool"] as string,
        input: (state["input"] as unknown) ?? null,
        completed: state["status"] === "completed",
        errorText: typeof state["error"] === "string" ? (state["error"] as string) : null,
      });
    }
    if (e["type"] === "text") {
      const text = (part["text"] as string | undefined) ?? "";
      if (text) run.finalText = text;
    }
    if (e["type"] === "step_finish") run.completed = true;
  }
  return run;
}
