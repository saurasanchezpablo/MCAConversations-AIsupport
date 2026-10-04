# MCA: Conversations AI

**Talk to your MCA villagers in your own words, and watch them act on it.**

MCA: Conversations AI turns every [Minecraft Comes Alive: Reborn](https://modrinth.com/mod/minecraft-comes-alive-reborn)
villager into someone you can really talk to. They answer in character and in your language, remember
you, do what you ask them, keep their word, and live a village life of their own.

> Based on **[MCA: Conversations](https://modrinth.com/mod/mca-conversations) by otectus**, the
> conversation system this edition builds on. Thanks to otectus, and to Luke100000 and Conczin for MCA
> Reborn. This edition **replaces** the original (same mod id): install one or the other.

---

## 💬 Real conversations
- Right-click a villager and type, the way you would talk to a person.
- They remember: promises you made, wishes they let slip, secrets they confided, grudges they hold.
- What you say counts. Kindness and cruelty move hearts and moods, and the village talks about it.
- Spanish and English (and more), in the player's own language.

## 🪓 Ask them to do things
- **"Chop 20 logs", "mine some stone", "go fishing"**: they go, a bar shows their progress, and
  they come back and hand it over.
- **"Cook this meat", "smelt this iron"**: they take it to the furnace and bring it back.
- **"Build me a hut here"**: hand over the materials and they build it block by block. Neighbours
  can help.
- **Guide you, wait for you, fetch from chests, breed animals, follow you, go home.**
- **No axe? They ask to borrow yours**, and start the moment you lend it.

## ✅ What they say is what they do
- Say yes, and they do it.
- Say no, and nothing happens.
- Can't do it, and they tell you why, without pretending.
- They never thank you for something you did not give them.

## 🏘️ A living village
- **Festivals, market days, harvest feasts, weddings, funerals, births, welcomes**: villagers gather,
  invite you, and remember who came.
- **Elections**: two candidates, campaign promises, and votes you can sway. The winner's promise
  shapes the village.
- **Neighbours chat among themselves** (you can overhear them), quarrel, and accept your help making
  peace.
- **Dates**: ask a villager out. They will be there, and they will remember if you were not.
- **Children grow up remembering how you treated them.**
- **They notice what you take from their bag**: from a friend it is fine, from a stranger it is theft.
- **Real needs, attacks on the village, and skills they learn** from practice and from you.

## ✨ Nice touches
- Bubbles above heads when a villager has something to tell you: **!** something important, **…**
  news, **♪** an invitation, **❤** a date.
- An on-screen indicator of who you are talking to, and a **`/diary`** of where you stand with
  everyone.
- **Optional voiced lines** with emotion and intent (OpenAI or Gemini TTS, configured on your client).

---

## Requirements
- Minecraft **1.21.1**, **NeoForge 21.1**
- **[MCA Reborn](https://modrinth.com/mod/minecraft-comes-alive-reborn)** 7.7.13 or newer
- An AI chat endpoint set up in MCA (`/mca chatAI`): MCA's service, OpenAI, or any OpenAI-compatible
  server, including local ones
- Install on **client and server**

## Setup
1. Configure MCA's chat AI as MCA describes.
2. In `config/mcaconversations-common.toml`, set `[ai] enabled = true`.
3. Right-click a villager and start talking.

Every feature can be switched on or off in the config. The full guide is on
[GitHub](https://github.com/saurasanchezpablo/MCAConversations-AIsupport).

## License & credits
GPL-3.0. AI edition by **Pablo Saura**. Original **MCA: Conversations** by **otectus**. **MCA Reborn** by
**Luke100000** and **Conczin**.
[Source code](https://github.com/saurasanchezpablo/MCAConversations-AIsupport) ·
[Issues](https://github.com/saurasanchezpablo/MCAConversations-AIsupport/issues)
