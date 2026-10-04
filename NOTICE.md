# Notice

**MCA: Conversations AI** is a modified version of **MCA: Conversations**.

| | |
|---|---|
| Original work | MCA: Conversations, © otectus. https://modrinth.com/mod/mca-conversations |
| This version | MCA: Conversations AI, modifications © 2026 Pablo Saura (saurasanchezpablo). https://github.com/saurasanchezpablo/MCAConversations-AIsupport |
| Built for | Minecraft Comes Alive: Reborn, by Luke100000 and Conczin. https://modrinth.com/mod/minecraft-comes-alive-reborn |
| License | GNU General Public License v3.0 only (GPL-3.0-only). See LICENSE.md |

## Statement of changes (GPL-3.0, section 5a)

This version was modified by Pablo Saura from **upstream MCA: Conversations 1.8.0 (NeoForge
1.21.1)**, starting in October 2026. The changes are:

- **AI conversations.** Villagers answer free conversation through MCA's chat-AI endpoint. A
  structured reply format is parsed into effects from a closed vocabulary, and a policy layer decides
  what each exchange may change. The layer covers hearts within budgets, moods, dispositions,
  memories, promises, wishes, grudges, gossip and opinions of neighbours.
- **Spoken requests.**
  - Actions: work tasks, errands, cooking, building, gifts, lending tools, handing over what was
    gathered, and group help.
  - Detection: requests are read from the player's own words (Spanish and English).
  - Consistency: a layer keeps what a villager says in line with what happens in the game.
- **Village and social life.**
  - Village events, including elections.
  - Villagers talking among themselves, mediation, dates, children's memories, secrets and betrayal.
  - Needs, threats and defence, skills and teaching.
  - Noticing changes made to a villager's own inventory.
- **Presentation.** Villager bubbles, a conversation indicator, a player diary, and voiced lines with
  acting direction (OpenAI and Gemini TTS) on the client.
- **Packaging.** Metadata, the version line (2.0.0-ai), the name, the documentation, and in-game
  GameTests.

The original files keep their own headers and history; the git history records every change. The
original README is preserved in `docs/upstream/README.md`.
