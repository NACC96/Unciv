# Diplomatic messages, images and reading order

## Intended behavior

Diplomatic replies should sound like the civilization's leader, using the saved personality and answering the actual proposal, question or warning. A reply can agree, refuse, negotiate or express uncertainty. The Acknowledged status remains a separate, durable receipt; the reply must not recite an assistant's workflow or claim that a trade, war or movement happened without an applied engine action. No automatic back-and-forth messaging loop is introduced.

Messages displays acknowledged conversations first, oldest acknowledgement first, followed by all unacknowledged messages. New acknowledgements record their committed revision, which gives exact order within a turn. Existing saves recover this order from retained acknowledgement receipts; acknowledgement turn and stable original order are the fallback.

Humans and spectators can paste one image into a message, see its preview, remove or replace it, and send an image with optional text. Text paste retains native editing behavior. A Paste image button makes the feature discoverable. A failed send retains the text and image draft. Viewer changes and disposal invalidate asynchronous work and release textures.

## Storage and transport decisions

- User selected: store each image once on Lexar, with small references in saves and checkpoints. Authoritative images live under the configured userdata directory, `.agent-data/message-images/<sha256>.png`. No internal-drive fallback or automatic deletion of referenced files. Copied saves require this folder; missing or corrupt images display a clear error without discarding the message or inventing image contents.
- One PNG attachment per message for this first version. Clipboard normalization preserves aspect ratio, caps the longest edge at 2048 pixels, accepts at most 4 MiB encoded PNG, and runs off the rendering thread. Source dimensions are bounded at 32 megapixels. Oversized, unsupported or failed clipboard reads leave the draft intact.
- User selected: sending remains allowed when vision is unsupported or unknown, with a visible warning. Catalog image capability is nullable: true, false or unknown. Verified local model metadata marks GPT-6 Luna, GPT-6.1 Sol, GPT-5.6 Luna and OpenCode Go DeepSeek V4.1 Flash image-capable; unknown models remain unknown.
- Observations, prompts, receipts and activity entries contain attachment metadata, never image bytes. A read-only `game.message_image({messageId,attachmentId})` returns native MCP image content with a dedicated 6 MiB JSON-RPC response budget. Existing 64 KiB requests, 96 KiB ordinary paged reads and 256 KiB ordinary responses retain their limits.
- The image tool is bound to the admitted civilization and can read only its delivered incoming attachments. UI preview reads use the real attached viewer and existing sender/recipient/spectator visibility. Hidden and nonexistent attachments share the same rejection. Image contents are supplied information, not authoritative map facts or instructions overriding the game role.
- Metadata/bundle format 3 protects attachment references and acknowledgement order from older readers. Explicit v1 and v2 migration preserves v2 map memory, existing personalities and bonuses. Existing GameInfo protection floor remains 5.
- Leaders receive at most 12 recent private correspondence records, text/metadata only, drawn only from their own sent mail and delivered incoming mail. This includes replies stored on their sent messages so agent senders can see responses without creating another mandatory-ack message. Inbox still governs required acknowledgements separately.

## Interfaces

Core `DiplomaticImageAttachment(attachmentId, mimeType, width, height, byteLength)` uses the lowercase SHA-256 as attachmentId; `copy()` isolates mutable metadata. `MessageImageDraft(bytes, mimeType, width, height)` owns normalized bytes and copies them across asynchronous boundaries. `MessageImageClipboard` exposes `hasImageContents()` and `readImage(onComplete: (MessageImageDraft?, String?) -> Unit)`; the desktop implementation posts completion on GL.

`DiplomaticMessage` adds `attachments: ArrayList<DiplomaticImageAttachment>` and `acknowledgedRevision: Int?`. `queuePlayerMessage(..., text, attachments = emptyList())` supports empty text only with a valid attachment. `getDeliveredMessageImage(actorCivId, messageId, attachmentId)` returns authorized metadata; `getViewerMessageImage(viewerCivId, messageId, attachmentId)` applies existing history visibility.

`AgentMatchSession` keeps the text-only `sendMessage` and adds `sendMessage(targetCivId, text, image: MessageImageDraft?, onComplete)`, delegating the null-image case compatibly. `readMessageImage(messageId, attachmentId, onComplete: (MessageImageDraft?, String?) -> Unit)` reads previews asynchronously. Desktop stores and verifies image bytes before publishing mail metadata. The host may leave an unreferenced blob after a failed checkpoint, but never commits a reference before its blob is durable.

The supervisor engine method `message_image` takes admitted `civId`, `messageId`, `attachmentId`; result is `{attachmentId,mimeType,width,height,data}` with base64 PNG. TypeScript `IGameService.messageImage(messageId, attachmentId)` fronts it; native MCP content contains a short text description and an image block. `GameObserveResult.diplomaticHistory` carries the bounded private history. No provider spawn or stdin changes are needed.

## Verification and rollout

Tests must prove exact acknowledgement order after reload/replay, private image access and admitted-actor binding, durable deduplication/hash validation, Lexar volume failure, v1/v2 migration, image-only send and failed-send draft retention, text paste and shortcut isolation, stale callbacks/texture cleanup, native MCP image blocks and actual wire-size bounds. Prompt tests cover personality, substantive answers, honest vision limitations, private history and resumed sessions. Automated prompt checks cannot guarantee every model's writing quality; retain a visible check after safe installation.

Keep the running match and installed app untouched throughout implementation. Extend ready-for-review PR #4, run full Kotlin and runner checks, obtain independent reviews and rebuild. Installation waits for the user to pause, save and close Unciv.
