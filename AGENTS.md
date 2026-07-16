# AGENTS.md

Guidance for agents working on Chronicler, a Kotlin Paper plugin that captures server activity, generates newspapers, renders books and a web/RSS edition, and optionally calls an LLM.

## Read First

- Read `README.md`, `build.gradle`, `src/main/resources/config.yml`, both plugin descriptors, and all source in the subsystem being changed. Defaults and persisted formats are public contracts.
- `Chronicler.kt` constructs and reloads the whole runtime. `tracker/` records events and sessions, `news/` owns JSON persistence/generation/book/web/archive behavior, `task/` owns scheduling and delivery, `llm/` owns blocking HTTP providers, and `command/`/`config/` own the operator surface.
- Runtime data lives below `plugins/Chronicler/`: `events.json`, `sessions.json`, `subscriptions.json`, `publish-state.json`, `draft.json`, `archive/`, imports/exports, and generated web files. Preserve tolerant decoding and recoverability.
- `plugin.yml` supports classic command discovery while `paper-plugin.yml` declares Paper dependencies. Keep their version/main/API metadata aligned with `build.gradle`; the legacy descriptor is currently stale at `1.8.1` while Gradle and the Paper descriptor say `1.8.2`.

## Threading and Lifecycle

- Bukkit/Paper entity, world, inventory, command, and book APIs belong on the appropriate server/global-region thread. Newspaper/LLM work runs through Paper's async scheduler and returns to the global scheduler for publication.
- LLM provider methods use synchronous `HttpClient.send`; never invoke them on an event or server thread. Provider availability is currently checked synchronously during enable/reload, so changes there can affect startup latency.
- Trackers include high-volume `PlayerMoveEvent`, block, projectile, chat, and world events. Keep handlers bounded and free of disk/network I/O. `EventStore` is synchronized, but tracker-local maps/sets assume event-thread access.
- Treat reload as disable plus enable. The current reload path stops publication and ticker and saves stores, but does not stop the old `WebRenderer` or unregister the old PlaceholderAPI expansion/listeners; verify port ownership and duplicate handlers before claiming reload safety.
- Stop scheduled work and embedded HTTP service before saving state. Do not access plugin state after disable, and test online players during reload and shutdown.

## Privacy and Persistence

- Privacy flags mainly affect newspaper generation, not collection: chat text, block/world coordinates, teleports, signs, kick reasons, player UUIDs, and other raw details may still enter `events.json`. Do not describe those settings as preventing capture without changing and migrating the storage path too.
- Private-message tracking stores only command type, not message text. Preserve that boundary and never log prompts, API keys, chat/private text, or complete sensitive event payloads.
- JSON store load failures are often intentionally tolerated, but silent failure can hide data loss. Prefer atomic writes, explicit recovery logging, and backward-compatible defaults; never assume a fresh plugin directory.
- `event-limit` is global despite the config comment saying “per category”; `recordAll` intentionally bypasses pruning for issue-zero backfill. Consider memory and archive retention when changing this.
- Publication must archive/render/deliver successfully before advancing state and removing consumed events. Draft edits are persistent and imports must remain confined to the imports directory with duplicate issue protection.

## Functional Contracts

- Template-only generation is a first-class fallback. An unreachable or malformed Ollama/OpenAI-compatible/Anthropic/LM Studio/CoCore response must not prevent a usable issue.
- Preserve MiniMessage localization, Adventure written-book limits and pagination, archive JSON compatibility, RSS/HTML escaping, subscription defaults, and drop-at-feet delivery when inventories are full.
- Real-time and in-game schedules have different time bases. Test DAILY/WEEKLY/BIWEEKLY/MONTHLY/custom ticks, server restarts, clock boundaries, issue zero, and concurrent manual/automatic requests guarded by `generationInProgress`.
- `enabled: false` currently suppresses scheduled publication but does not prevent tracker registration, web startup, metrics/update checks, or event capture. Do not infer a full plugin-off switch from its name.
- Keep automatic updates restricted to GitHub release JARs with a matching SHA-256 asset and atomic placement into Paper's update directory.

## Build and Validation

- The Gradle wrapper is authoritative. The build requires a Java 26 toolchain, emits JVM 25 bytecode, targets Paper `26.1.2`, runs JUnit 5, creates the shaded JAR, and produces a SHA-256 sidecar: run `./gradlew clean build`.
- Tests cover schedule timing, parsing, log backfill, stores/serialization, generator, book rendering, and updater helpers; there is no complete live-server integration suite. Inspect `build/libs/Chronicler-<version>-all.jar` and its checksum.
- On a disposable matching Paper server, exercise first install, existing-data upgrade, enable/disable/reload, PlaceholderAPI/Vault absent and present, every provider plus template fallback, issue-zero backfill, draft publication, import/export/retention, web/RSS/search, delivery/full inventory/subscription, updater failure, and clean shutdown.
- Preserve unrelated local work. In particular, inspect the worktree before editing; generated `.gradle/` and `build/` state are not source, and credentials belong only in runtime configuration.
