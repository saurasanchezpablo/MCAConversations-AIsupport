# AI conversations

MCA's villager chat AI, with consequences. A player talks to an MCA villager in normal chat, MCA's
ChatAI answers as before, and the exchange now also counts: it can move hearts, leave a lingering
mood, nudge the villager's disposition toward the player, and leave a short memory the villager will
bring up later. Off by default (`ai.enabled` in `mcaconversations-common.toml`).

Code: `src/main/java/dev/otectus/mcaconversations/ai/`, plus `compat/mca/McaChatAi.java` and
`mixin/OpenAIChatAIMixin.java`. Tests: `src/test/java/dev/otectus/mcaconversations/ai/`.

## What MCA provides, and what this takes over

MCA's ChatAI (verified by decompiling every build in the probe fleet, 7.6.20 to 7.7.1-beta.2):

| Step | MCA class | This integration |
|---|---|---|
| Notice a chat line addressed to a villager (name, nickname, or an open 2400-tick conversation) | `MixinServerPlayNetworkHandler`, `ChatAI.getVillagerForConversation` | unchanged |
| Pick the villager's strategy (Inworld character or OpenAI-compatible) | `ChatAI.computeStrategyIfAbsent` | unchanged; Inworld is never touched |
| Build the prompt, call the endpoint, parse `{message, optionalCommand}`, run the command | `OpenAIChatAI.answer` (<= 7.7.0, synchronous, common pool) / `OpenAIChatAI.requestAndApply` (>= 7.7.1, async, server thread) | **replaced** by `AiConversations` when `ai.enabled` |
| Deliver the line from the villager | `villager.conversationManager.addMessage` | unchanged |

So MCA still owns routing, endpoint/model/token (`/mca chatAI`), its prompt modules, the
operator-edited context (`/mca chatAI context`), the command allow-list, and delivery. The hook is one
`@Pseudo` mixin with one `require = 0` injector per MCA generation; `MixinTargetProbeTest` checks that
every fleet build has exactly one of the two methods. Everything MCA-side is bound by name in
`McaBinding` (`CONFIG_CHAT_AI_*`, `CHAT_AI_*_MODULE`, `TRIGGER_*`), so no MCA type appears in `src/`.

Why take over the request instead of wrapping it: MCA's `OpenAIChatAI.post` deserialises the reply
into a two-field record and returns only the line, so any structured outcome is lost before a wrapper
could see it. And MCA's own short-term dialogue memory is keyed by villager only (player B sees player
A's conversation) and is lost on restart.

## One turn

1. **Server thread.** `AiSessions.admit`: one request in flight per villager-player pair, and a
   per-player cooldown (`ai.turnCooldownTicks`). Then an immutable `AiPromptInput` is captured:
   MCA's settings and prompt pieces, this mod's structured context (`AiContextCollector`), the pair's
   memories, the current transcript, and MCA's currently valid commands (only when MCA's
   `villagerChatAIUseTools` is on). From here the villager is identified by UUID only.
2. **Transport threads** (`HttpAiTransport`, two daemon threads, JDK `HttpClient`, hard timeout
   `ai.requestTimeoutSeconds`). Never the server thread.
3. **Server thread.** The villager is re-resolved by UUID in the player's current level. If the player
   logged out, the villager unloaded, died, changed dimension or is over 32 blocks away, the turn is
   discarded whole. Otherwise: `AiReplyParser` → `AiOutcomePlan` → `AiOutcomeApplier`, then the line is
   returned to MCA, which delivers it.

Every effect happens in step 3, after validation, within one tick. A failed turn (timeout, network,
provider error, unusable reply) applies nothing; MCA's hosted-service errors (`limit`,
`limit_premium`, `invalid_model`) show MCA's own messages, anything else one throttled action-bar line.

## The reply format

A superset of MCA's `StructuredResponse`, so MCA's field names keep working:

```json
{"message": "That means a lot to me.",
 "optionalCommand": "",
 "assessment": {"impact": "positive", "confidence": 0.85},
 "emotion": "grateful",
 "memory": {"text": "Steve thanked me for guarding the gate.", "importance": "medium"},
 "effects": [{"type": "disposition", "axis": "trust", "direction": "up"}]}
```

The model never names a number of hearts. Every field is parsed into a closed vocabulary with bounded
values; unknown fields, unknown effect types and out-of-range values are dropped individually. A
reply that is not valid structured output keeps its line and loses every effect. Text is stripped of
formatting codes and control characters and length-capped (480 code points for a line, 160 for a memory).

## Guardrails: the model requests, the game decides

`AiOutcomePlan` (pure, unit-tested):

| Sentiment | Authored hearts |
|---|---|
| strongly_positive | +3 |
| positive | +1 |
| neutral | 0 |
| negative | -2 |
| strongly_negative | -4 |

- Nothing but the line and the memory happens below `ai.minConfidence` (0.6).
- A lingering state (`grateful`, `proud`, `annoyed`) is left only when the emotion agrees with the
  judgement.
- A disposition nudge must point the way the judgement does (trust, respect and warmth rise with a
  positive exchange, tension with a negative one). Neutral exchanges nudge nothing. Attraction and
  familiarity are never nudgeable.

`AiOutcomeApplier` then sends hearts through the **same guard chain authored dialogue uses**
(`ProgressSavedData.applyAffection`):
- an idempotent transaction id per turn;
- `conversationHeartMultiplier` and `strongerNegativeOutcomes`;
- diminishing returns per decision per day (full, half, nothing);
- a per-conversation budget of +4/-5 (standard depth; a conversation ends after `ai.conversationIdleTicks` of silence);
- the shared daily caps `conversationDailyPositiveCap`/`NegativeCap` (8/10).

Hearts are then paid through MCA's own `rewardHearts`, which plays heart particles, adds
interaction fatigue, fires the hearts advancement, shifts mood and doubles negatives for sensitive
villagers. AI chat and the topic menus therefore share one daily allowance and one relationship.

## Consequences currently supported

| Effect | Mechanism | Who reads it later |
|---|---|---|
| Hearts | affection guard chain + MCA `rewardHearts` | MCA (marriage, procreation, gifts, interactions), relationship bands |
| Lingering mood toward the player | `StateTracker` (`grateful`, `proud`, `annoyed`) | authored dialogue conditions, scene selection |
| Disposition nudge | `Dispositions.apply` (farming guard, daily axis cap) | checks, stance bias, relationship bands (trust margin) |
| Contact credit | `Relationships.creditContact` | lived-friendship requirement of the bands |
| Memory | `AiMemorySavedData` | the next AI prompts |
| Reaction | `ConversationOutcomes.react` | Townstead/Emotecraft, cosmetic |
| MCA command | MCA's `TriggerCommandInfos.findCommand` | MCA (follow, stay, go home, trade window...) |

Adding an effect type: a record in `AiEffect`, a parse branch in `AiReplyParser.parseEffect`, a line
in the schema in `AiPromptBuilder`, a planning rule in `AiOutcomePlan` and an apply step in
`AiOutcomeApplier`. Nothing the parser does not know can reach the world.

## Memory and persistence

- **Long-term** (`data/mcaconversations_ai_memory.dat`, overworld `SavedData`, keyed by villager UUID
  and player UUID):
  - Up to `ai.memoriesPerPair` (12, hard cap 32) one-sentence memories with importance, sentiment and day.
  - Low-importance memories fade after 7 days, medium after 30; high stay until displaced.
  - A repeated memory is reinforced instead of duplicated.
  - At most 4096 pairs, least recently written evicted.
  - Dropped when the villager dies; expired entries are pruned at server start.
- **Short-term** (`AiSessions`, RAM only): the last 8 lines (2000 characters) of the current
  conversation, the in-flight flag and the per-conversation budget. Raw chat is never saved.
- Hearts, states and dispositions live where they always did (MCA's villager NBT, the progress and
  disposition stores), so they survive restarts, unloads and dimension changes like everything else.

## Context given to the model

MCA's own pieces, in MCA's order:
- the session tags for the hosted service;
- the system prompt;
- the operator-edited context (7.7.0 and later);
- the six prompt modules: personality, traits, relation, village, environment, advancements;
- MCA's child/relative rule.

This mod adds labelled sections from the typed context snapshot every scripted conversation reads:
- relationship band and hearts, family roles, familiarity, first met and last spoke;
- earlier AI exchanges, a falling-out, promises due;
- disposition bands (not raw numbers) and lingering moods;
- the villager's mood, activity, interests, values and social style;
- time, season, weather, place and recent village news;
- what the villager has heard of the player (MCA: Reputation, MCA: Crime).

Then up to 8 memories. Only known values are included; nothing about other players.

## Multiplayer

All state is keyed by the (villager UUID, player UUID) pair; there is no per-villager-only state.
Two players talking to the same villager have separate transcripts, budgets and memories (MCA 7.7.1
additionally serialises requests per villager). A player talking to two villagers has two
conversations. Every write happens on the server thread.

## Known limitations

- **Prompt injection is bounded, not prevented.** A player can try to talk the model into a glowing
  assessment. The worst case is the daily heart allowance of a well-played scripted conversation:
  +8 per villager per day by default, with diminishing returns. A model-written memory could also carry
  an instruction into later prompts; memories are capped, sanitised and presented as data, and their
  effect is held to the same caps.
- **Chat mode can also answer.** If this mod's typed chat mode is on for a player and they name a
  villager, both chat mode and MCA's ChatAI may reply. That predates this integration.
- **Endpoint compatibility.** Any OpenAI-compatible chat-completions endpoint works. `requestJsonMode`
  improves reliability where supported. MCA's hosted service is supported as MCA uses it, but whether
  it follows the extended format depends on the hosted model.
- **MCA 7.6** has no operator-edited context, so that part of the prompt is absent there.
- **Gossip** of AI memories to other villagers is not implemented (follow-up below).

## Follow-ups worth doing

- Publish high-importance memories as `GossipEvent`s so a village reacts to how a player treats one of its own.
- Let authored topics opt in to an `ai_unlock` gate that an AI effect can open, by id from a whitelist the topic declares.
- An operator command to list or clear a pair's AI memories (`/conversations ai memories <villager>`).
- A NeoForge 1.21.1 mirror, if this fork tracks the port.

## Manual test plan

Not automated, because the mod's tests are pure JUnit with no Minecraft bootstrap. With `ai.enabled=true`,
`debugAi=true` and MCA's chat AI configured:

1. Praise a villager → `[ai] reply ... impact=positive ... granted=1`; hearts particle.
2. Insult → negative hearts, `annoyed` state; scripted dialogue reacts.
3. Small talk → `impact=neutral granted=0`.
4. Praise five times → diminishing grants, then 0 at the daily cap.
5. Two villagers → separate memories in the next prompt.
6. Two players, one villager → no memory or transcript crosses over.
7. Save, quit, reload → memories are recalled.
8. Walk away until unloaded mid-request → `discarded` in the log, nothing applied.
9. Wrong endpoint → a grey action-bar line, nothing applied.
10. Model returning plain text → the line is delivered, no effects.
11. A dedicated server with two clients.
12. Topic menus and chat mode, with `ai.enabled=false` and then `true`.
13. Hearts near MCA's limits and at a daily cap of 0.
14. A server restart mid-conversation → transcript gone, memories kept.
