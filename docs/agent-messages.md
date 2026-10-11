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

Mail, delivery markers and retry metadata are saved with the match and checkpoint. Old saves start with empty mail. Player/spectator sends are metadata commits and preserve the gameplay revision, so they do not invalidate an agent's in-progress action. Peer messages are diplomatic claims, and spectator messages are guidance; neither is authoritative game state or permission to change tools, rules or role. Prose alone does not create peace treaties, trades or other engine effects.
