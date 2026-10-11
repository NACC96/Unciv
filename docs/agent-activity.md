# Watching agent activity

Agent matches show a narrow, floating **Agent activity** feed below Fog of War. It refreshes every 350 ms while the surrounding map and controls stay usable. **Activity** next to Pause/Resume hides or shows it; the feed's **×** also hides it. It has no modal backdrop and does not capture input outside its rectangle.

The nation selector defaults to **Follow active nation**, showing the current agent's activity while **Live** is enabled. **All nations** combines the available transcripts; choosing a nation keeps its transcript selected as turns change. Nation options show the civilization and its configured agent name. Scrolling back or expanding a tool stops Live and holds the current nation, so the next turn does not replace what you are reading. **Live** switches Follow active back to the latest active agent; fixed nation and All selections stay selected. For spectators, Follow active retains the last agent during a human turn or between active turns. Hiding and reopening the feed preserves the selected filter.

Thinking and progress messages appear as paragraphs. Tool calls appear as concise summaries such as **Read game state**, **Moved unit 2 to (4, 0)**, and **Selected research: Pottery**. Summaries come from the actual call and result, without another model request. Consecutive calls from the same agent and turn show only the latest summary with a count such as **10 tool calls · Expand**. Click that summary or count to reveal every call, then click an individual call to expand its full arguments, result, and provider event inline. **Copy details** copies them. Messages, thinking, errors, rejected calls and agent/turn changes break the group, so failures remain visible. Calls still in progress say **Moving**, **Reading**, etc.; rejected actions say **Action rejected**.

The feed follows new activity gradually, at 40 logical pixels per second. Mouse-wheel steps are 24 logical pixels. Scrolling or expanding an entry stops following so you can read earlier activity; the position stays anchored to the entry you are reading while other text grows. The footer counts new entries in the selected feed; hidden nations do not affect its unread count or scroll position. **Live** jumps to the newest entry and resumes following. Choosing a different filter clears the unread count, resets the initial 120-entry window and jumps to its newest entry. **Earlier** loads another 120 entries. Earlier content stays available while new activity arrives. Headings show each agent's configured display name in a stable, distinct color, plus its civilization portrait, civilization name and turn. Color is an additional identifier alongside the name and portrait.

The feed displays what the provider exposes: visible progress messages, reasoning summaries or thinking events, summarized tool calls/results, and errors. A provider may withhold internal reasoning or emit a summary only. Codex requests detailed reasoning summaries; the game prompt asks leaders to explain strategic intent in visible progress messages. Full diagnostic and run/usage events remain in the logs.

Spectators can inspect activity from every agent. Human players can see only their own civilization's activity, including available thinking and full tool details; the nation selector and All mode keep that same limit. Opponents' private activity is not available through the feed or its detail lookup.

Each message and thinking entry repeats the agent's colored display name, so identity stays visible during long turns. Tool groups and errors carry the same identifier. The floating panel uses a rounded translucent dark background so the map stays visible without pixel readback or blur passes.

Overview and Messages retain the shared glass renderer. Its pixel readback avoids the framebuffer-to-texture copy that previously crashed Apple's OpenGL driver. The activity feed does not instantiate this renderer.

Nation filters and collapsed tool titles shrink to the available panel width, displaying an ellipsis for long text. Nation dropdowns stay inside the viewport even with long custom agent names; the underlying names and transcript details remain complete.

New Codex profiles default to **GPT-6 Luna / XHigh**. Luna offers Low, Medium, High, XHigh and Max; GPT-6.1 Sol additionally offers Ultra. Previously saved profiles keep their choices. The agent editor keeps **Save** and **Cancel** below its scrolling fields, including in small windows. **Enter** saves; **Escape** cancels. Dropdown changes preserve the selected reasoning level when it is supported by the new model.

Each match saves durable logs under the Unciv data directory:

```
.agent-data/logs/<match-id>/run-*/
  meta.json       # PID, command (bearers redacted), cwd, civilization, turn, model, session, exit
  pid
  events.jsonl    # original provider stream
  activity.jsonl # indexed live activity
  stderr.log
```

For spectators, **Logs** opens the match's directory. It is disabled for human viewers and when no log directory is available. On macOS the directory is inside `~/Library/Application Support/Unciv`. Full bodies remain on disk; the feed caches only the bodies in its current display window. Hiding the feed does not pause the game. Pause waits for the active agent to finish normally. Startup diagnostics and transport bookkeeping stay in the logs without crowding the feed.

Leaving a match for the main menu, setup, loading or map editor stops new agent turns. An already admitted turn keeps its game tools connected and finishes normally. Returning to the retained match leaves it paused until **Resume**. City, research, policy and diplomacy screens remain part of the open match. Replacing a match or closing the application retires its session: the current turn drains, then its supervisor exits normally. Disposed screens cannot receive queued render updates, and closed sessions cannot restart.

Leaders receive shared game instructions on every invocation, plus civilization-scoped turn context and private strategy notes. Codex receives the shared instructions as developer instructions, Claude as appended system instructions, and every provider also receives them with its turn prompt. Examples cover observation, legal unit/city choices, founding, movement, research, production, notes revisions, action receipts and end-turn. Only validated game commands mutate the engine.

The macOS window uses logical display dimensions, supports resizing, and keeps an agent match attached to the existing world screen during resize.

The map and minimap display a fresh, isolated game snapshot after each committed engine action, including unit moves, city founding and end-turn. Camera pan/zoom and valid selections survive the swap. The spectator view is rebound to the new snapshot too; provider messages alone never count as applied moves.

The activity feed uses a rounded translucent background without blur. On an isolated Apple M4 Pro Retina component benchmark, the usual 300×680 panel took 0.46 ms for the plain completed draw versus 4.97 ms on a blur refresh, with roughly 9 refreshes/second. This measures the component, not whole-game FPS. Overview and Messages retain their existing rendering.
