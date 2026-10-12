import { summarizeTool, toolResult } from "./tool-summary.js";

/** Image bytes are transport-only; provider/activity logs retain metadata. */
const imageBlockMarker = /\\*"type\\*"\s*:\s*\\*"(?:image|input_image|image_url)\\*"/;

const imageDataUriMarker = /data:image\/[a-z0-9.+-]+(?:;[^,\s]*)?,/i;
const containsImage = (value: string) => imageBlockMarker.test(value) || imageDataUriMarker.test(value);

export function redactImageData(value: unknown): any {
  if (typeof value === "string") {
    if (!/^[\s]*[\[{"]/.test(value)) return containsImage(value) ? "[image-containing text omitted]" : value;
    try {
      const parsed = JSON.parse(value);
      const redacted = redactImageData(parsed);
      return JSON.stringify(redacted) === JSON.stringify(parsed) ? value : JSON.stringify(redacted);
    } catch { return containsImage(value) ? "[unparseable image event omitted]" : value; }
  }
  if (Array.isArray(value)) return value.map(redactImageData);
  if (!value || typeof value !== "object") return value;
  const block = value as Record<string, unknown>;
  if (block["type"] === "image" || block["type"] === "input_image" || block["type"] === "image_url") {
    return { ...Object.fromEntries(Object.entries(block).filter(([key]) => !["data", "source", "url", "image_url"].includes(key)).map(([key, val]) => [key, redactImageData(val)])),
      ...(typeof block["mimeType"] === "string" ? {} : block["source"] && typeof block["source"] === "object"
        ? { mimeType: (block["source"] as Record<string, unknown>)["media_type"] } : {}),
      imageData: "[omitted]" };
  }
  return Object.fromEntries(Object.entries(block).map(([key, val]) => [key, redactImageData(val)]));
}

export function redactImageLine(line: string): string {
  try {
    const parsed = JSON.parse(line);
    const redacted = redactImageData(parsed);
    return JSON.stringify(redacted) === JSON.stringify(parsed) ? line : JSON.stringify(redacted);
  } catch { return containsImage(line) ? "[unparseable image event omitted]" : line; }
}

/** Only provider-exposed content is displayed; image bytes are omitted. */
export interface ActivityEntry {
  id: string;
  kind: string;
  title: string;
  text: string;
  timestamp: string;
  summary?: string;
  status?: string;
  durationMs?: number;
}
const pretty = (value: unknown): string => typeof value === "string" ? value : JSON.stringify(value, null, 2) ?? "";

/** Only actor-bound engine receipts establish a visible acknowledgement. Provider streams are not authoritative. */
export function acknowledgementActivity(receipt: any, actorCivId: string): ActivityEntry | null {
  const message = receipt?.result;
  if (receipt?.status !== "applied" || message?.targetCivId !== actorCivId || typeof message?.messageId !== "string" ||
      !Number.isInteger(message?.acknowledgedTurnSerial) ||
      typeof message?.acknowledgementText !== "string" || !message.acknowledgementText.trim()) return null;
  return { id: `acknowledgement-${message.messageId}`, kind: "message", title: "Message acknowledged",
    text: message.acknowledgementText, summary: message.acknowledgementText, status: "completed", timestamp: new Date().toISOString() };
}

export class ActivityNormalizer {
  private seq = 0;
  private tools = new Map<string, { name: string; input: any }>();
  private messageId = "";
  private blocks = new Map<number, { type: string; text: string; id?: string; name?: string; input?: any; json?: string }>();

  fromLine(line: string): ActivityEntry[] {
    line = redactImageLine(line);
    const timestamp = new Date().toISOString();
    const entry = (kind: string, title: string, text: string, id = `event-${++this.seq}`) => ({ id, kind, title, text, timestamp });
    let e: Record<string, any>;
    try {
      const parsed = JSON.parse(line);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return [entry("message", "Provider output", line)];
      e = parsed;
    } catch { return [entry("message", "Provider output", line)]; }
    if (e.item) {
      const item = e.item;
      const id = `item-${item.id ?? ++this.seq}`;
      if (item.type === "mcp_tool_call") {
        const result = item.result?.content?.map((c: any) => {
          if (typeof c.text !== "string") return pretty(c);
          try { return pretty(JSON.parse(c.text)); } catch { return c.text; }
        }).join("\n\n") ?? pretty(item.result);
        const description = summarizeTool(item.tool, item.arguments, toolResult(item.result?.content ?? item.result), item.error || item.result?.isError ? "failed" : item.status);
        return [{ ...entry("tool", description.title, `Arguments:\n${pretty(item.arguments)}\n\nResult:\n${result || "Waiting for result…"}${item.error ? `\n\nError:\n${pretty(item.error)}` : ""}\n\nProvider event:\n${pretty(e)}`, id), ...description }];
      }
      if (item.type === "agent_message" || item.type === "reasoning") {
        return [entry(item.type === "reasoning" ? "reasoning" : "message", item.type === "reasoning" ? "Reasoning summary" : "Agent message", item.text ?? pretty(item.summary ?? item), id)];
      }
      const diagnostic = item.type === "error" && item.message?.startsWith("Under-development features enabled:");
      return [entry(diagnostic ? "diagnostic" : item.type === "error" ? "error" : "system", item.type ?? e.type, item.message ?? pretty(e), id)];
    }
    if (e.type === "assistant" || e.type === "user") {
      return (e.message?.content ?? []).flatMap((b: any, i: number) => {
        if (b.type === "tool_use") {
          const input = pretty(b.input);
          this.tools.set(b.id, { name: b.name, input: b.input });
          return { ...entry("tool", b.name, `Arguments:\n${input}\n\nWaiting for result…`, `tool-${b.id}`), ...summarizeTool(b.name, b.input, undefined, "started") };
        }
        if (b.type === "tool_result") {
          const tool = this.tools.get(b.tool_use_id);
          return [{ ...entry("tool", "Tool result", `Arguments:\n${pretty(tool?.input)}\n\nResult:\n${pretty(b.content)}`, `tool-${b.tool_use_id}`), ...summarizeTool(tool?.name ?? "Tool", tool?.input, toolResult(b.content), b.is_error ? "failed" : "completed") }];
        }
        return entry(b.type === "thinking" ? "reasoning" : "message", b.type === "thinking" ? "Thinking" : "Agent message", b.text ?? b.thinking ?? pretty(b), `message-${e.message?.id ?? ++this.seq}-${i}`);
      });
    }
    if (e.type === "stream_event") {
      const event = e.event;
      if (event?.type === "message_start") { this.messageId = event.message?.id ?? `stream-${++this.seq}`; this.blocks.clear(); return []; }
      if (event?.type === "content_block_start") {
        const block = event.content_block;
        if (block?.type === "text" || block?.type === "thinking" || block?.type === "tool_use") {
          this.blocks.set(event.index, { ...block, text: block.text ?? block.thinking ?? "", json: "" });
          return [];
        }
      }
      const delta = event?.delta;
      if (delta) {
        const block = this.blocks.get(event.index);
        if (!block) return []; // Unknown raw events remain in events.jsonl.
        if (block.type === "tool_use") {
          block.json = (block.json ?? "") + (delta.partial_json ?? "");
          let input = block.input;
          try { input = JSON.parse(block.json); } catch { /* An incomplete tool input is still running. */ }
          return [{ ...entry("tool", block.name ?? "Tool", `Arguments (streaming):\n${block.json}`, `tool-${block.id}`), ...summarizeTool(block.name ?? "Tool", input, undefined, "running") }];
        }
        block.text += delta.thinking ?? delta.text ?? "";
        return [entry(block.type === "thinking" ? "reasoning" : "message", block.type === "thinking" ? "Thinking" : "Agent message", block.text, `message-${this.messageId}-${event.index}`)];
      }
    }
    if (e.part) {
      const p = e.part;
      if (e.type === "tool_use") {
        return [{ ...entry("tool", p.tool, pretty(p.state), `part-${p.id ?? ++this.seq}`), ...summarizeTool(p.tool, p.state?.input, toolResult(p.state?.output), p.state?.status ?? "running") }];
      }
      if (e.type === "text" || e.type === "reasoning") return [entry(e.type === "reasoning" ? "reasoning" : "message", e.type === "reasoning" ? "Reasoning" : "Agent message", p.text ?? pretty(p), `part-${p.id ?? ++this.seq}`)];
    }
    return [entry(e.type === "error" || e.is_error ? "error" : "system", e.type ?? e.event ?? "Provider event", pretty(e))];
  }
}
