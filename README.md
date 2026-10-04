# MCA: Conversations AI

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange)
![Requires](https://img.shields.io/badge/Requires-MCA%20Reborn%207.7.13%2B-blue)
![License](https://img.shields.io/badge/License-GPL--3.0-lightgrey)
![Status](https://img.shields.io/badge/Status-beta-yellow)

**Talk to Minecraft Comes Alive: Reborn villagers in your own words.** They answer through an AI model,
remember you, do what you ask, keep their word, and live a village life of their own.

> **Credits.** This is a fork of **[MCA: Conversations](https://modrinth.com/mod/mca-conversations) by
> otectus**, which provides the conversation system, dialogue content, gossip, dispositions and living
> histories this edition builds on. It is an add-on for
> **[Minecraft Comes Alive: Reborn](https://modrinth.com/mod/minecraft-comes-alive-reborn)** by
> Luke100000 and Conczin. The AI edition is developed by **Pablo Saura**
> ([saurasanchezpablo](https://github.com/saurasanchezpablo)). See [NOTICE.md](NOTICE.md).

---

## What it adds

### Conversations
- **Free conversation.** Right-click a villager (or just type in chat near them) and talk. Each
  villager answers in character, in your language, through the chat-AI endpoint configured in MCA.
- **Memory.** Villagers keep short memories of you, and promises, wishes, grudges and secrets persist
  across sessions.
- **Consequences, with guardrails.** An exchange can move hearts, leave a mood, change how they see you
  or a neighbour, and spread as village gossip. The model only proposes these; the game applies them
  within daily budgets, so nothing the model writes can run commands or create items.
- **Words and deeds agree.** If a villager says they will do something, they do it. If they refuse,
  nothing happens. If they cannot do it (no tool, nothing to give, a grudge), their line is rewritten
  so it is true before you hear it. They never claim to have received something you did not give.

### Ask them to do things, by word
- **Work.** Ask them to chop wood, mine stone and ore, fish, hunt or harvest, optionally "20 logs". A
  boss bar shows progress, and they come back and hand it over.
- **Errands.** Ask them to guide you somewhere, wait at a place, pick up items, store or fetch from
  chests, breed animals, or cook and smelt at a nearby furnace, smoker or blast furnace.
- **Build together.** Ask for a hut, a pen, a campfire, a field, a lit path or a wall. You hand over
  the materials, and they (and any neighbours you bring in) build it block by block.
- **Give and lend.**
  - Gifts: you choose what to give from your whole inventory.
  - Tools: lend them a tool when they need one, and they start working the moment they have it.
  - Their bag: they notice if you go through their inventory, and how they react depends on your
    relationship. Taking back a borrowed tool is fine; a stranger calls taking their things theft.
- **Group help.** "Everyone, follow me" or "get Bob to help you chop" brings in villagers nearby.

### A living village
- **Village events.** Festivals, harvest feasts, market days, funerals, weddings, births, welcomes,
  town meetings after attacks, and **elections** with candidates, campaign promises and votes you can
  sway. Villagers gather, invite you, and remember who came.
- **Social life.**
  - Neighbours chat among themselves, and you can overhear them.
  - Quarrels break out, and you can mediate.
  - Old grudges fade with time.
  - Children grow up remembering how you treated them.
- **Romance.** Ask a villager out. They will wait for you at the agreed place and remember how the
  date went, or that you never came.
- **Real needs.** A villager may need a tool for their trade, food, firewood or a bed. Helping them is
  tracked as a promise.
- **Threats.** Villagers notice the monsters you kill to defend the village, are frightened after
  attacks, and thank a Hero of the Village.
- **Teaching.** Villagers improve at tasks with practice and with your help, and can teach you the
  recipes of their trade.

### Presentation
- A bubble above a villager's head when they have something to tell you: **!** something important,
  **…** news, **♪** an invitation, **❤** a date.
- An on-screen indicator of who you are talking to.
- Optional **voice**. Villagers' lines are spoken with acting direction (emotion, intent and delivery)
  in Spanish or English, through OpenAI or Gemini text-to-speech, configured on the client.
- **`/diary`** (or `/diario`). Shows where you stand: people, promises, dates, events, elections,
  rumours. Add `book` to get it as a written book.

Everything from the original MCA: Conversations is still here: topics, gossip, dispositions,
greetings and the dialogue menus.

## Requirements
- Minecraft **1.21.1** with **NeoForge 21.1**
- **[MCA Reborn](https://modrinth.com/mod/minecraft-comes-alive-reborn)** 7.7.13 or newer (7.x)
- An OpenAI-compatible chat endpoint for MCA's chat AI (MCA's own service, OpenAI, a local server...)
- Install on **both client and server**.

This edition uses the same mod id as the original (`mcaconversations`). It **replaces** MCA:
Conversations, so do not install both. Configs and worlds from the original keep working.

## Getting started
1. Install MCA Reborn and this mod.
2. Set up MCA's chat AI (endpoint, model, token) with `/mca chatAI` or MCA's config, as MCA documents.
3. Enable AI conversations in `config/mcaconversations-common.toml`:
   ```toml
   [ai]
   enabled = true
   ```
4. Walk up to a villager, right-click them (the chat opens) and talk. Sneak + right-click opens MCA's
   usual menu.

Optional switches:

| File | Setting | What it does |
|---|---|---|
| common | `ai.aiOnly` | Replaces MCA's scripted dialogue with AI conversations only |
| common | `ai.autoConversations` | Villagers start conversations with you |
| common | `ai.talkOnClick` | Right-click starts an AI conversation |
| server | `ai.relationshipEffects`, `ai.gameplayEffects` | What an exchange may change |
| server | `ai.villageEvents`, `ai.villageEventChance` | Village life events |
| server | `ai.villagerChatter`, `ai.villagerChatterCooldownTicks` | Villagers talking among themselves |
| server | `ai.bubbles` | Bubbles over villagers' heads |
| client | `[voice]` | Engine, API keys and models for voiced lines |
| client | `display.showVillagerBubbles` | Draw the bubbles |

In-game voice setup: `/mcavoice status | test | provider | key | model | scripted | debug`.

Full design notes, guardrails and the manual test plan are in
[docs/AI-CONVERSATIONS.md](docs/AI-CONVERSATIONS.md).

## Building from source
```bash
./gradlew build                 # jar in build/libs/, plus unit tests
./gradlew runGameTestServer     # in-game tests against a real MCA villager
```
Java 21 is required. The system JDK may be older; point `JAVA_HOME` at a JDK 21.

## License
GPL-3.0-only, like the original mod and MCA Reborn. See [LICENSE.md](LICENSE.md) and
[NOTICE.md](NOTICE.md). The original project's README is kept in
[docs/upstream/README.md](docs/upstream/README.md).
