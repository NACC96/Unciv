# Diplomatic Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Natural leader replies, pasted message images and acknowledgement-ordered conversations.

**Architecture:** Keep the existing durable private mailbox and acknowledgement receipts. Store image blobs once under Lexar userdata, deliver them through an actor-bound MCP read tool, and expose a bounded private conversation history for continuity.

**Tech Stack:** Kotlin/Scene2D, Java AWT/ImageIO, TypeScript, MCP SDK, JUnit, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-11-diplomatic-messages-design.md`

## Global Constraints

- Running match and installed app remain untouched until safe shutdown.
- One PNG attachment, 2048-pixel longest edge, 32-megapixel source bound, 4 MiB encoded image, 6 MiB image JSON-RPC response.
- Store once under userdata `.agent-data/message-images/<sha256>.png`; never fall back from Lexar or delete existing user data.
- Unsupported/unknown vision warns but does not block sending.
- Metadata/bundle format 3; preserve v1/v2 saves and v2 map memory; GameInfo floor stays 5.
- No bytes in observations, prompts, receipts or activity; delivered-recipient image access and existing UI mail privacy remain enforced.
- Acknowledged first in committed read order, all unacknowledged last; private history maximum 12.
- Extend PR #4 ready for review; no merge or installation during implementation.

## Review Focus

- A clipboard callback arrives after viewer change or disposal: ignore it and release image resources.
- Lexar disappears or a stored blob is corrupted: preserve draft/mail, fail explicitly, never commit a dangling new reference.
- Multiple acknowledgements in one turn or replay after reload: preserve exact committed order.
- An image is sent to a model without vision: warning remains visible, no fabricated description or empty-turn failure loop.
- An agent guesses another nation's message/image ID: identical hidden/nonexistent rejection, no bytes or correspondence leak.

### Task 1: Durable messages, image storage and migration

**Files:** Modify `DiplomaticMessage.kt`, `AgentMatchState.kt`, `AgentMapKnowledge.kt`, `MatchCoordinator.kt`, `AgentObservation.kt`, `AgentMatchSession.kt`, desktop `DesktopAgentMatch.kt`; create core `MessageImage.kt` and desktop `agents/FileMessageImageStore.kt`; add agent-message storage/privacy/migration tests and update format-dependent tests.

**Interfaces:** Produce the exact core/session/host DTOs and methods in the spec, `ObservationDto.diplomaticHistory`, and desktop `message_image` dispatch. Consume normalized PNG draft bytes from Task 3. Add `ModelSpec.supportsImages: Boolean? = null` and preserve unknown values; annotate only verified models listed in the spec.

- [ ] Write failing tests for image-only mail, private image metadata access, exact `acknowledgedRevision` after commit/replay, metadata-only save copies, deduplicated durable blobs and corrupted/missing blobs. Add v2 migration preserving map knowledge and acknowledgement receipts; use future version 999 in rejection tests.
- [ ] Run focused Kotlin message/store/migration tests and inspect expected failures before implementation.
- [ ] Implement metadata and host-owned blob storage with bounded PNG validation, hash verification, atomic durable publication and required-volume checks. Store the blob before the metadata commit; keep text-only session compatibility and authenticated viewer capture. Backfill old acknowledgement order from receipts. Add bounded authorized history sorted by recent message/acknowledgement activity.
- [ ] Run focused tests, including admitted-actor RPC spoofing, unknown/hidden image IDs, original metadata/copy isolation and failed store/commit. Report commands and results; commit explicit owned paths only.

### Task 2: Native image tools and natural leader prompts

**Files:** `agent-runner/src/{fake-game-mcp,engine-protocol,stdio-engine,game-mcp-server,provider-spawn,turn-prompt,game-instructions,supervisor,tool-summary,activity}.ts`, relevant tests, `agent-protocol/README.md` and schemas if affected.

**Interfaces:** Consume Task 1 metadata/result and `diplomaticHistory`. Produce `IGameService.messageImage(messageId: string, attachmentId: string)` and `game.message_image`. Never fetch image files directly or add shell/file tools.

- [ ] Write failing tests for true image content, separate encoded-wire budget, rejection/privacy propagation, prompt metadata without bytes, private correspondence and resumed sessions. Test leader-specific reply instructions and absence of the stock acknowledgement example.
- [ ] Run focused Vitest tests and inspect failures.
- [ ] Add the read-only tool and stdio method, validating PNG/base64 and wire bytes before returning. Add the allowlist entry and concise tool summary; strip image bytes from normalized/journaled tool activity. Update all prompt seams to answer as the leader in the saved personality, inspect attachments first, distinguish claimed evidence from facts, state inability honestly and avoid receipt-only boilerplate. Make correspondence responses visible without creating an automatic message loop.
- [ ] Run runner typecheck/build/full tests, report results and commit owned paths.

### Task 3: Clipboard and Messages interface

**Files:** Desktop `AwtClipboard.kt` and new `MessageImageNormalizer.kt`; `AgentMessagesPanel.kt`; `AgentMessagesPanelTest.kt`; new clipboard/normalizer and ordering tests.

**Interfaces:** Consume Task 1 DTO/session image methods and nullable model image capability. Produce asynchronous clipboard drafts, preview/removal, image-only send and `orderedDiplomaticMessages` UI ordering helper (or equivalent pure tested ordering).

- [ ] Write failing tests for image paste versus text paste, shortcut isolation, preview/send/remove, retained failed draft, stale callbacks, and acknowledged-first order including multiple same-turn acknowledgements and old records.
- [ ] Run focused JUnit tests and inspect failures.
- [ ] Implement AWT clipboard image read and bounded aspect-preserving PNG normalization off GL; upload textures only on GL and dispose them when replaced/hidden history rebuild/disposed. Use a composer capture listener for image paste only, preserving native text editing. Show Paste image, preview with remove, and a persistent vision warning; send text or image and clear only on success. Order history by acknowledgement turn/revision, then all pending messages; keep pending visible at the bottom and preserve scrolling where possible.
- [ ] Run UI/normalizer tests, render at normal and compact sizes, report results and commit owned paths.

### Task 4: Integration, review and package

**Files:** Tests/artifacts and this plan's completion record; PR #4 description/review comments.

**Interfaces:** Consume all three tasks; no new application interfaces.

- [ ] Run full Gradle `check`, runner typecheck/build/tests and native `:desktop:macApp` with the existing APFS build override and Lexar userdata configuration. Count actual XML results.
- [ ] Obtain independent reviews of host persistence/privacy, Node image transport/prompts and UI asynchronous lifecycle; post findings on PR #4 and fix actionable issues with reproducing tests.
- [ ] Verify packaged bytecode/runner includes changes, strict native signing and Lexar data configuration; record artifact hash and validation limits.
- [ ] Update PR #4 title/body to the final scope and pending live check. Request safe pause/save/quit only when ready to install; install and reopen after confirmation, then verify paste, read order and natural replies visibly with the user if Java input remains unavailable.
