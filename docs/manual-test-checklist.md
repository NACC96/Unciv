# Manual Test Checklist — desktop & real-provider acceptance

Everything below REQUIRES a human, an interactive desktop session, and (for
provider runs) your paid plans. Nothing here is automated or claimed as
verified. Execute top to bottom; stop at the first failure and record it.

## 0. Build & launch (no provider needed)

1. `./gradlew :desktop:dist` → run `java -jar ~/.unciv-build/desktop/libs/Unciv.jar`
2. Ordinary (non-agent) game: create, play 3 turns, save, load — confirm
   nothing regressed without a session.
3. Path with spaces: copy the jar + `agent-runner/dist` + `agent-runner/node_modules`
   into a directory containing spaces; set `-Dunciv.node.path` if needed.
4. Missing-dependency error: temporarily rename the node binary → new agent
   match must show a CLEAR error naming the searched locations.
5. Finder launch (double-click, no dev shell): agent match finds bundled Node.

## 1. Agent-match setup (no provider: use the shakedown first)

6. New game → set one slot to Agent → start gate accepts; invalid combos
   (mods, espionage, random count) are rejected with the new-game message.
7. Spectator + agent civs; spectator view works without input authority.

## 2. Provider shakedown (free OpenCode model — short is fine)

8. Attach an opencode agent to one civ; Run → observe: status text cycles
   (Starting provider → Agent deciding → Applying), map updates WITHOUT
   reopening the screen after each action, checkpoint dir grows under
   `--data-dir/.agent-data`.
9. Pause mid-turn: status flips to Paused; provider activity drains; Run
   resumes; no duplicated actions after resume (revision continuity).
10. Take over human → the civ's turn answers to the UI; return-to-agent with
    a different model → fresh provider session, no stale resume.

## 3. Completed matches (paid plans — these are the acceptance runs)

11. Codex completed match: small Vanilla map, agent civ(s) played to a
    victory/defeat/elimination outcome; record provider, model, CLI version,
    turns, outcome, interventions (none = pass).
12. Claude completed match: same bar.
13. Three-provider match: ≥3 major civs, one per CLI (codex/claude/opencode),
    played to an outcome; record per-civ outcomes and any interventions.

## 4. Human+agent interaction

14. Human civ + agent civs: human unit orders, city queue, research, policy,
    war/peace, friendship, trade accept — each lands in committed state (map
    reflects it; revision advances) — NOT through legacy paths.
15. Human receives a demand from an agent civ → decision surfaces without a
    modal (inspector path) and blocks that agent's end turn until answered.
16. Save mid-match, reopen: identical decision boundary; provider session
    either resumes cleanly or restarts without duplicating committed actions.
17. Both inspection settings: private-only vs full inspection view switches
    without leaking the agent's private notes.

## 5. Later-game & performance

18. Load/prepare a multi-city, many-unit position: observation payload size
    bounded, tool replies bounded, no GL-thread stalls while providers think
    (UI stays responsive during Agent deciding).
19. Sample and record: checkpoint duration, command round-trip, render
    stalls.

## Pass criteria

- Every completed match: outcome recorded, zero silent automation, zero
  human decision-taking for agent civs.
- No step required reopening/restarting the app to see agent actions.
- All failures: capture status text, last committed action id, and the
  checkpoint generation number before reporting.
