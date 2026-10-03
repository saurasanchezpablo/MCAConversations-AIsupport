# MCA: Conversations (AI-support fork) - Forge 1.20.1 mod

## This fork
`saurasanchezpablo/MCAConversations-AIsupport` is a personal fork of otectus's MCA: Conversations,
an add-on for Minecraft Comes Alive: Reborn. Its purpose is to add AI (LLM) support to villager
conversations. Everything below the fork sections is inherited from upstream and still applies.

- There is no `upstream` remote configured; `origin` is the fork. Ask before adding one.
- The baseline is upstream 1.8.0 (`mod_version` in `gradle.properties`). Keep fork changes in
  separate commits so upstream releases can still be merged.
- Upstream docs (`README.md`, `CHANGELOG.md`, `CONFIG.md`, `DATAPACK.md`, `docs/`) describe upstream.
  Don't rewrite them for fork work; put fork design notes in new files under `docs/` (prefix `AI-`).

### AI conversations (implemented)
Design, guardrails and manual test plan: `docs/AI-CONVERSATIONS.md`. Code: `ai/`, `compat/mca/McaChatAi.java`,
`mixin/OpenAIChatAIMixin.java` (hooks MCA's `OpenAIChatAI.answer` <=7.7.0 / `requestAndApply` >=7.7.1).
Hearts go through `ProgressSavedData.applyAffection` + MCA `rewardHearts`, never directly.

### Rules for AI features
- **Never block the server thread.** Model calls run off-thread (`CompletableFuture` on a dedicated
  executor) and their results are applied back on the server thread via `server.execute(...)`. A
  conversation must stay usable while a request is in flight.
- **Authored content stays the fallback.** If the AI path is disabled, misconfigured, times out or
  errors, the existing template/scene selection runs unchanged. AI is opt-in and off by default.
- **Generation runs server-side** (the server holds villager state); the client only renders. Never
  ship provider credentials to clients.
- **Config**: the `ai.enabled` switch and debug flag are Common; balance numbers are Server (`ai.*`),
  following the repo's split (`ConfigSpecTest` pins both). Endpoint, model and token are MCA's own
  (`/mca chatAI`); never add a second copy, never log the token.
- **Prompt context comes from the existing model of the villager** (`context/`, `personality/`,
  `disposition/`, `history/`, `gossip/`, `village/`) rather than new reflective MCA reads. Any new MCA
  access still goes through `compat/mca/McaBinding`.
- HTTP: `ai/HttpAiTransport` (JDK `HttpClient`, own daemon pool). Don't add an HTTP dependency.
- Model output only ever becomes effects through `AiReplyParser` (closed vocabularies) → `AiOutcomePlan`
  (pure policy) → `AiOutcomeApplier`. A new effect type touches all three plus the schema text.
- Untrusted model output is sanitized (length cap, strip formatting codes and control characters)
  before it reaches chat or the dialogue screen.
- Unit-test the AI layer without network: put the provider behind an interface and use a fake in
  `src/test`.

## Orientation
Registration inventory and package layout live in `MODMAP.md`. Read that instead of crawling `src/`.
(Upstream generates it with `.mcmod-tools/modmap.py`, which is not in this repo; edit only outside
the `MODMAP:AUTO` block, or leave it alone.)

This is an MCA Reborn add-on. It registers no blocks, items, or entities at all: everything it
does is mixins, events, and reflective calls into MCA.

Where dialogue is decided, for hooking AI in:
- `scene/` - `ConversationPlanner`, `ConversationDirector`, `FallbackChain`: which scene/line is picked
- `template/` - `TemplateEngine`, `TemplateContextFactory`, `SlotRenderer`: how a line is rendered
- `chat/` - typed-chat mode: `IntentMatcher`, `ChatModeDispatcher`, `ChatDelivery`
- `conversation/` - session state (`ConversationSession(s)`), choices (`ChoiceSelectionService`)
- `network/` - S2C/C2S choice packets; bump `network_protocol` in `gradle.properties` on any codec change

## Quick Reference
- **Mod ID**: `mcaconversations`
- **Package**: `dev.otectus.mcaconversations` (kept as upstream's, to stay mergeable)
- **MC / Forge / Java**: see `gradle.properties`; Java 17, mappings `official`
- **Mixin config**: `src/main/resources/mcaconversations.mixins.json` (separate `client` list)

Version numbers live only in `gradle.properties`; `processResources` expands them into `mods.toml`
and `pack.mcmeta`. Never hard-code one elsewhere, this file included.

## Build
The system JDK on this machine is 11, which ForgeGradle 6 rejects; the foojay toolchain resolver
downloads JDK 17 on first build. If Gradle itself complains, set `JAVA_HOME` to a JDK 17. The first
build decompiles Minecraft and is slow (several minutes); use long timeouts.

- `./gradlew compileJava` - compile only, the normal iteration loop
- `./gradlew build` - full build, output in `build/libs/`; also runs `conversationsReports`
- `./gradlew check` - JUnit 5 tests. The drift checks `verifyGeneratedConversationContent` and
  `verifyVoiceOverlays` are separate tasks; `check` does not depend on them, run them by name
- `./gradlew test --tests '<FQCN or pattern>'` - a single test class
- `./gradlew runClient` / `runServer` - dev runs with MCA (`mca_version`)
- `./gradlew townsteadProbeTest -PtownsteadModernJar=... -PtownsteadLegacyJar=...` - Townstead
  binding check against real jars; skipped unless the properties are supplied
- CI (`.github/workflows/build.yml`) runs `build verifyGeneratedConversationContent verifyVoiceOverlays`

## Content pipeline
`src/content/{topics,professions,voices}` are the hand-authored sources. `generateConversationContent`
and `generateVoiceOverlays` compile them into `src/main/resources`. These are
**not** wired into `processResources`: the generated output is committed, and the `verify*` tasks fail
when it drifts. Edit `src/content/`, re-run the generator, commit both sides.

## Structure
```
chat/ conversation/ scene/ template/  - dialogue flow, session state, line selection
context/ disposition/ gossip/ history/ personality/ progress/ village/  - the model of a villager
client/          - client-only code and client UI; common code must not import it (convention only,
                   nothing enforces it)
ai/              - AI conversations over MCA's ChatAI (fork feature; see docs/AI-CONVERSATIONS.md)
compat/mca/      - McaBinding / McaHandles: every MCA class and member resolved by name at runtime
compat/{townstead,quests,seasons,reputation,crime}/  - same probe-and-stub pattern per optional mod
                   (quests, reputation and crime compile against the vendored api jars in libs/api/)
mixin/, mixin/client/  - mixin targets, split to match the mixins.json lists
```

## Conventions
- **No MCA import may ever appear in `src/`.** MCA is bound reflectively through
  `compat/mca/McaBinding.java`, which probes both package roots (`forge.net.mca.*` and
  `net.conczin.mca.*`) so one jar spans MCA's renames. `McaBindingProbeTest` replays the manifest
  against every jar in `mca_probe_versions`, each in its own class loader. `No*StaticLinkTest`
  enforce the same for the other optional mods.
- Optional-mod compat classes are never `@Mod.EventBusSubscriber`-annotated when that would put the
  other mod's classes on the classpath; they register manually instead (see `compat/quests/`).
- Events use `@Mod.EventBusSubscriber`, defaulting to the FORGE bus. Only setup-time subscribers
  set `bus = Bus.MOD`.
- Config is three `ForgeConfigSpec`s (Common/Server/Client) in `McaConversationsConfig`; anything
  read on a dedicated server belongs in Common. `ConfigSpecTest` covers the specs.
- No access transformers, and MixinExtras is not used.
- Lang keys must exist in every locale; the content checks fail otherwise.

## Terminology
- "Response card" in code and docs = the numbered dialogue choice shown to the player.

## Family compatibility (upstream rules)
MCA: Reputation, MCA: Quests, MCA: Crime, MCA: Conversations and MCA: Mob Compatibility are one family
of MCA Reborn add-ons, and Ultima Kingdoms consumes their APIs. The sibling repos, the
`MCAReputation/docs/FAMILY_COMPATIBILITY.md` tuple and the `1.21.1 Ports/` NeoForge mirror are **not**
part of this fork; don't go looking for them. The rules still constrain changes here:

- **MCA Reborn is the only mandatory dependency** (`[7.6,8)`), bound by name across its package
  roots. Architectury is never declared mandatory.
- **Companion ranges carry a lower bound only** (`[x.y,)`), except Townstead: `[0.7.5,0.9)`.
- **Sibling APIs are consumed through vendored, hash-pinned compile-only jars** (`libs/api/`,
  `gradle/sibling-apis.properties`, `verifySiblingApis`). Each sibling has one adapter package, loaded
  by name after `ModList.isLoaded`, and a static-link test keeps its types out of everything else.
- **MCA probe fleet** in `gradle.properties` (`mca_probe_versions`) is shared family-wide; don't
  change it for fork reasons.
- **Load order runs from provider to consumer**: MCA, MCA: Reputation, then MCA: Quests / Conversations
  / Crime / Mob Compatibility, then Ultima Kingdoms. Never declare a cycle.
