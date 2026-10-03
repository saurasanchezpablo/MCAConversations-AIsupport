# AI conversations

MCA's villager chat AI, with consequences. A player talks to an MCA villager in normal chat, MCA's
ChatAI answers as before, and the exchange now also counts: it can move hearts, leave a lingering
mood, nudge the villager's disposition toward the player, and leave a short memory the villager will
bring up later. Off by default (`ai.enabled` in `mcaconversations-common.toml`).

Code: `src/main/java/dev/otectus/mcaconversations/ai/`, plus `compat/mca/McaChatAi.java` and
`mixin/OpenAIChatAIMixin.java`. Tests: `src/test/java/dev/otectus/mcaconversations/ai/`.

## NeoForge 1.21.1

This branch carries the same feature as the Forge 1.20.1 build, file for file. On 1.21.1, MCA (7.7.33,
7.7.36-beta.3, root `net.conczin.mca`) still uses the synchronous `OpenAIChatAI.answer`, called on a
common-pool thread by MCA's `MixinServerGamePacketListenerImpl`, so the `answer` injector is the live one.
The `requestAndApply` injector is a `require = 0` no-op until MCA's 1.21.1 line adopts the asynchronous
strategy that its Forge 7.7.1 line already has. Loader differences: `ModConfigSpec` instead of
`ForgeConfigSpec`, and `AiMemorySavedData` uses the 1.21.1 `SavedData.Factory` / `HolderLookup.Provider`
API. The saved payload is identical on both loaders.

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

## Social life (the second layer)

Thirteen behaviours that make villagers feel like people. Each one is a request the model may make,
checked by `AiOutcomePlan` against `AiTurnFacts` (exactly what the model was shown that turn) and
applied by `AiSocialEffects` / `AiPromises` through the system that already owns that kind of change.

| # | Behaviour | What the model may say | Who decides / what applies it |
|---|---|---|---|
| 1 | **Village talk** | (automatic) a strongly felt exchange with a non-secret memory | New gossip types `PLAYER_KINDNESS` / `PLAYER_CRUELTY` in the village log. Scripted gossip tells them only to the player concerned ("Alice told me how kind you were"). Other villagers' AI prompts include them. |
| 2 | **Promises** | `promise` {item?, count 1–8, days 1–7} | `AiPromises`: gifts add up toward item promises, and talking on time keeps a promise to return. One day past due it is broken. Kept: +3 hearts once, grateful, trust, a HIGH memory, Reputation `promise_kept`. Broken: −3 once, annoyed, trust down, a memory, `promise_broken`. |
| 3 | **Quests** | `offer_quest` {one of the villager's eligible offers} | MCA: Quests' commission menu, restricted to that quest, opens after the line. The player still chooses. |
| 4 | **Deeper topics** | `unlock_topic` {confided} | The player-scoped unlock memory that the authored "secret" topic already checks (friends only). |
| 5 | **Wishes** | `wish` {item, days} | A gift of that item within the window gives +2 hearts once, grateful, a memory and a spoken thank-you. |
| 6 | **Opinions of neighbours** | `opinion` {neighbour, warmth/trust/respect, up/down, cause} | Kept per villager (−3..3), shown back to the model, and mirrored into living-histories opinions when enabled. Only unambiguous names of real residents are accepted. |
| 7 | **Bystanders chime in** | `interjection` {speaker, message} | Only a teen or adult villager within 8 blocks, shown to the model with their profession, relationship and memory of the player. They walk over and speak through their own MCA queue, and remember having joined in. |
| 8 | **Romance** | `attraction` nudge, emotion `smitten` | Only for an adult, non-relative villager who is single or partnered with this player, and only from MCA's courtship threshold (10 hearts). Otherwise the prompt tells the villager to refuse flirting. |
| 9 | **Grief** | (automatic) | When a villager dies, their partner, parents, children and siblings mourn for 21 days. The prompt says so. Cruelty to the bereaved costs 1.5× hearts, and comfort earns trust. |
| 10 | **Reputation** | (automatic) | MCA: Reputation's own incidents: `promise_made` / `promise_kept` / `promise_broken`, and `public_apology` bound to a known grievance. |
| 11 | **Directions** | `directions` {village building, or for friends a structure MCA rumours about} | The game computes compass and distance (plus rough coordinates for far structures, once a day per pair, throttled server-wide). The villager says it after the reply. |
| 12 | **Prices** | `discount` (friends) | Vanilla villager gossip (`MINOR_POSITIVE`), which MCA trade prices are made of. Rudeness adds `MINOR_NEGATIVE`. At most 20 points per pair per day; it spreads and fades the vanilla way. |
| 13 | **Refusal and apology** | `grudge`, `forgive` | A strongly hurtful exchange (or an explicit grudge) makes the villager refuse trading (a vanilla `Villager.startTrading` hook covers every MCA path), help and directions for 1.5 days. A sincere apology lifts it, clears annoyed, lowers tension and records a Reputation apology. |

What makes it read as a person:
- The prompt now has a **"how they talk"** block: plain short speech, opinions, questions back, no assistant tone, never mentioning game mechanics.
- The villager carries their **own state**: grief, grudges, promises owed, a wish, views of neighbours, who is listening, and what the village says.
- Extra lines (directions, thanks, interjections) are spoken through **MCA's own message queue**, after the reply, so they sound like the villager.

## AI-only villagers and villager-started conversations

**One path for typed chat.** With `ai.enabled`, this mod routes typed chat itself (`AiChatRouter`) and
MCA's own chat-AI routing is silenced (`OpenAIChatAIMixin` returns nothing), so a line is answered
once. MCA's `enableVillagerChatAI` no longer needs to be on, but the endpoint, model and token are
still MCA's. A message goes to, in order:
1. a villager named in it (full or first name, accents ignored);
2. the villager you are already talking with (within 16 blocks, conversation still live);
3. the villager you are looking straight at, within 6 blocks.

Otherwise it is just chat. Replies go through the villager's MCA message queue, so they look and
sound like MCA's chat AI.

**`ai.aiOnly`** (common, default false):
- MCA's **Talk** button no longer opens MCA's scripted dialogue tree
  (`InteractionDialogueInitMixin` on `InteractionDialogueInitMessage.handleServer`). The interaction
  screen closes, the villager greets you through the AI, and you reply in chat.
- This mod's scripted typed chat (chat mode) is off.
- Gifts, trading, follow/stay, work and the family tree are unchanged.

**`ai.autoConversations`** (common, default true): villagers start AI conversations themselves
(`AiInitiative`, every 10 s).
- The villager comes over (MCA's `DeliverMessageTask`) and opens with the strongest reason on its mind:

  | Weight | Reason |
  |---|---|
  | 5 | a promise from you is due |
  | 4 | a promise you just kept |
  | 4 | a fresh loss (acquaintances and closer) |
  | 3 | something the village says about you |
  | 3 | you are their partner |
  | 3 | three or more days without seeing a friend |
  | 2 | a wish |
  | 1 | small talk, or curiosity about a stranger |

- Chance per check is `autoConversationChance` (0.1) × weight.
- Limits: one opening per player per `autoConversationCooldownTicks` (5 min), the same villager at
  most every 10 min, and never while you are talking to someone or in a menu.
- No villager comes over while sleeping, panicking, busy with another player, holding a grudge, or
  when the relationship is tense or hostile.
- An opening line is heard, not judged: it never moves hearts or makes promises.
- While this is on (or `aiOnly`), this mod's scripted greetings and initiatives are off.

## Voice (acting TTS)

Villagers speak their lines aloud with acting: the emotion, what the line is for, their mood, grief,
a grudge or romance, in the language of the line with a native accent.

**Flow.**
1. **The model.** Each AI reply carries a `delivery`: intent (thank, tease, warn, confess, refuse,
   flirt…), tone, pace, intensity and volume. Without one, it is derived from the emotion.
2. **The server.** `AiVoice` builds a `VoiceDirection` with the villager's state (gender, age,
   personality, mood, mourning, romance with this player, a grudge) and the player's game language.
   It sends it as `VoiceDirectionS2C` (protocol 5) to every player within 32 blocks, ahead of MCA
   delivering the line.
3. **The client.** A mixin on MCA's `SpeechManager.onChatMessage`, the one place every villager line
   reaches MCA's TTS, hands the line to `VillagerVoices`:
   - it matches the direction (exact text first, else "next line");
   - `VoiceScript` turns it into an actor's brief;
   - it synthesises with the configured engine;
   - it plays the PCM from the villager's position through the Voice volume slider (`PcmSoundInstance`,
     streamed straight to Minecraft's sound engine).

   MCA then stays silent, so a line is never spoken twice.

**Engines** (`mcaconversations-client.toml`, section `[voice]`; keys stay on the client):

| `provider` | Engine | Acting |
|---|---|---|
| `MCA` (default) | MCA's own TTS (`/mca tts`) | none, unchanged |
| `OPENAI` | `gpt-4o-mini-tts` via `/v1/audio/speech`, 24 kHz PCM | `instructions` = the brief |
| `GEMINI` | `gemini-2.5-flash-preview-tts`, AUDIO modality | brief prepended as a style prompt |

Keys: `openaiApiKey` / `geminiApiKey`, or the `OPENAI_API_KEY` / `GEMINI_API_KEY` environment variables.
Each villager keeps one voice that fits their gender and age (`VoiceCatalog`, chosen from the UUID).

**Language.**
- AI replies are written in the player's game language: the server reads the client language and
  tells the model.
- The brief tells the engine to speak each line *in the language it is written in*, with the accent
  the game language implies:
  - `es_es`: Spain; other `es_*`: Latin America;
  - `en_us`: American; `en_gb`: British.
- So a Spanish line is never read by an English voice. Scripted lines are voiced in whatever language
  they are shown in; this mod ships en_us and pt_br, so a Spanish client hears MCA's own Spanish lines
  in Spanish and this mod's untranslated ones in English.

**In-game setup and diagnostics.** `/mcavoice` is a client command: it runs on your client only,
and keys typed there are saved to `mcaconversations-client.toml`, never sent to the server.

| Command | Does |
|---|---|
| `/mcavoice status` | Engine, masked keys, models, game language, Voice volume, villager lines seen by the MCA hook, directions received, lines voiced / left to MCA / played, last synthesis time, last error |
| `/mcavoice test [text]` | Speaks a line from the nearest villager (or you) and reports success with timing, or the exact error |
| `/mcavoice provider mca\|openai\|gemini` | Choose the engine |
| `/mcavoice key openai\|gemini <key>` | Store an API key |
| `/mcavoice model openai\|gemini <model>` | Choose the model |
| `/mcavoice models` | List the speech models this key can use (click one to select it). A Gemini 404 also switches to the first listed TTS model automatically |
| `/mcavoice endpoint <url>` | OpenAI-compatible speech endpoint |
| `/mcavoice scripted on\|off` | Also voice scripted lines, or only AI conversations |
| `/mcavoice debug on\|off` | Log each voiced line |

**Cost guards.**
- Lines over `maxCharacters` are skipped, as are lines from villagers more than 32 blocks away, babies
  and zombified villagers.
- The last 48 rendered lines are cached in memory.
- `voiceScriptedLines = false` voices only AI conversations.

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

## Manual test plan (social layer)

1. Promise: *"I'll bring you 3 wheat tomorrow."* → `promise=true`. Gift 3 wheat on separate gifts; the third gift gives *"You actually remembered!"*.
2. Broken promise: promise, then wait more than a day past due and talk again → she brings it up; trust down.
3. Wish: chat until she mentions something she'd love; gift it → thank-you line, +2 hearts.
4. Grudge: insult her badly → sneak-click to trade → *"I'm not doing business with you today."* Apologise sincerely, then trade again.
5. Romance: flirt with a married villager → refused; with a single adult friend → may get `smitten`.
6. Grief: kill a villager's sibling (test world) → talk to the sibling → grief in the reply; comfort gives trust.
7. Directions: *"Where's the blacksmith?"* → line with compass and distance; as a friend, ask about a mineshaft.
8. Bystander: talk near a second villager → sometimes they walk over and chime in.
9. Village talk: be very kind to Alice, then talk to Bob in the same village → he has heard.
10. Quests (with MCA: Quests): ask if she needs help → the commission menu opens for one quest.

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
