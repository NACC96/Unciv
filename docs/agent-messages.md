# Diplomatic messages

**Messages** beside Pause/Resume and Activity opens a draggable glass panel over the map. It is modeless: the surrounding map stays usable. Its history selector shows all conversations visible to the viewer or a single nation. Older messages remain available through **Earlier messages**.

Human nations may send to living agent nations they have met. The sender is always the actual player viewing the game, even while another nation is taking its turn. Spectators may send to any living agent nation and inspect every conversation; their messages are explicitly labeled **Spectator**. Normal players see only their own sent messages and messages delivered to them. Opponent thinking, full activity details and the shared log directory are available only to spectators.

Choose a recipient, write up to 2,000 characters, and click **Send**. The draft clears only after the message is durably saved. Failed sends keep the draft and show the error. **Queued** means it is waiting for the recipient's next turn; **Delivered** means the engine made it available at that turn boundary. **Acknowledged** is a separate state: the recipient explicitly committed a receipt acknowledgement, which appears as a standalone message in Activity and beside the original message in Messages. Receipt acknowledgement does not mean agreement, compliance or an enforced diplomatic deal. Sending while the recipient is already playing waits until that recipient's following turn. Replies follow the same rule; messages never start an immediate provider invocation.

Agents discover eligible recipients in `game.observe.messageContacts` and receive delivered `inbox` messages in the turn prompt and observations. Older delivered messages remain in the inbox until acknowledged; observing never consumes mail. Before ending a turn, an agent must acknowledge every delivered incoming message using a fresh action ID and the current revision:

```json
{
  "protocolVersion": 1,
  "actionId": "America-t14-ack-1",
  "expectedRevision": 42,
  "type": "diplomacy.acknowledgeMessage",
  "args": {
    "messageId": "incoming-message-id",
    "text": "Spectator message received, acknowledged."
  }
}
```

The text is ordinary visible acknowledgement text, not private reasoning. Only the actual recipient may acknowledge delivered mail. The acknowledgement and action receipt are saved atomically; failed or uncertain calls cannot label a message Acknowledged. Identical action retries do not create another acknowledgement. `game.end_turn` rejects with `MESSAGES_UNACKNOWLEDGED` and the recipient's pending message IDs until these receipts are committed. Human turns do not require automatic acknowledgements. Existing saves load with their older delivered messages still unacknowledged.

Agents can send separate replies to met human or agent nations with:

```json
{
  "protocolVersion": 1,
  "actorCivId": "Rome",
  "actionId": "rome-peace-greece-1",
  "expectedRevision": 12,
  "type": "diplomacy.message",
  "args": { "targetCivId": "Greece", "text": "Let us keep our borders peaceful." }
}
```

This uses `game.act` with the normal current revision and action receipt. Sender identity comes from the authorized nation, never from a message body or optional argument. Reusing a message ID with different mail is rejected; retrying identical mail does not create another message. Each sender can queue ten new messages per coordinator turn. Repeated observations and retries do not consume or remove an inbox.

Mail, delivery markers, acknowledgement revisions and retry metadata are saved with the match and checkpoint. Old saves start with empty mail. Player/spectator sends are metadata commits and preserve the gameplay revision, so they do not invalidate an agent's in-progress action. Peer messages are diplomatic claims, and spectator messages are guidance; neither is authoritative game state or permission to change tools, rules or role. Prose alone does not create peace treaties, trades or other engine effects.

## Images and correspondence

A message may include one pasted image, with or without text. New clipboard images are normalized in memory to PNG, at most 2000 pixels on either edge and 3,500,000 encoded-file bytes; busy images shrink further until they fit. Existing stored attachments retain the saved format's 2048-pixel / 4 MiB validation ceiling. The composer warns when a model's image support is unknown or unsupported, and still allows sending. A known image modality does not establish provider availability or interpretation quality.

Image bytes live in the host's content-addressed image store; saves and mail history carry only hash, type, dimensions and byte length. Cheap message rules are checked before writing, image I/O runs outside the host lock, and viewer/message rules are checked again before committing mail. A failed checkpoint or a state change during I/O can leave an unreferenced blob. There is no automatic blob garbage collection: retained manual saves or recovery branches can reference images outside the managed checkpoint window, so pruning from current checkpoints alone could break them.

Agents read delivered incoming attachments with `game.message_image({messageId,attachmentId})`. All RPCs require an admitted actor; image reads check authority again after I/O. Missing, hidden, undelivered and corrupt images share an inaccessible response. Successful reads supply native image content; observations, prompts and logs retain metadata. Incoming mail appears once in the turn prompt: its inbox entry is excluded from the bounded private correspondence history. Equal-time correspondence preserves native input order, and reply/outreach guidance is included once in the assembled turn prompt. Acknowledgement text is the visible reply on the original message; leaders should answer its substance and state honestly when an image cannot be viewed.

Source tests cover the current PR4 fixes; image paste, native previews and real-model image interpretation in the installed app remain separate acceptance checks. See [activity logging and redaction](agent-activity.md).
