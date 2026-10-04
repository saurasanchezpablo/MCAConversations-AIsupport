# Publishing on Modrinth

## Project settings (create once)
Create the project at https://modrinth.com/dashboard/projects with these values.

| Field | Value |
|---|---|
| Name | MCA: Conversations AI |
| Slug / URL | `mca-conversations-ai` (must match `modrinth_project` in `gradle.properties`) |
| Summary | Talk to MCA Reborn villagers in your own words. They answer through AI, remember you, do what you ask, keep their word and live their own village life. |
| Project type | Mod |
| Categories | Game Mechanics, Social, Mobs |
| Additional categories | Adventure |
| Client side | Required |
| Server side | Required |
| License | GPL-3.0-only |
| Source code | https://github.com/saurasanchezpablo/MCAConversations-AIsupport |
| Issues | https://github.com/saurasanchezpablo/MCAConversations-AIsupport/issues |
| Icon | `project-icon-ai.png` (512×512) |
| Description | contents of `MODRINTH.md` (or run `modrinthSyncBody`, below) |

Gallery: use real in-game screenshots. The promotional video renders are illustrations; label them
that way if you use them.

## Uploading a version
1. Set the version in `gradle.properties` (`mod_version`, and `modrinth_version_type`: `alpha`, `beta`
   or `release`).
2. Write the release notes as the topmost section of `CHANGELOG.md`. It becomes the Modrinth
   changelog.
3. Create a personal access token at https://modrinth.com/settings/pats with the scopes *Create
   versions* and *Write projects*.
4. Run:
   ```bash
   export MODRINTH_TOKEN=<token>
   ./gradlew build            # tests must pass
   ./gradlew modrinth         # uploads build/libs/mcaconversations-neoforge-<version>+1.21.1.jar
   ./gradlew modrinthSyncBody # optional: updates the project description from MODRINTH.md
   ```

The upload sets these automatically: NeoForge, Minecraft 1.21.1, **MCA Reborn** as a required
dependency, and the original **MCA: Conversations** as incompatible, because both share the mod id.

## Credit and license checklist
- [x] `LICENSE.md`: GPL-3.0, unchanged.
- [x] `NOTICE.md`: original author, statement of changes, dates.
- [x] The mod list shows the original author in its credits (`mod_credits`).
- [x] README and project page credit otectus (MCA: Conversations), and Luke100000 and Conczin (MCA
  Reborn).
- [x] Source code is public on GitHub, as GPL-3.0 requires.
- [ ] As a courtesy, consider letting otectus know about the fork.
