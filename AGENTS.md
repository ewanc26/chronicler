# AGENTS.md

Guidance for agents working on Chronicler, a Kotlin PaperMC plugin that records server events and publishes an in-game newspaper.

## Structure and contracts

- `src/main/kotlin/` contains event listeners, persistence, article generation, commands, and book rendering.
- `src/main/resources/plugin.yml` is the Paper command/permission contract; keep it aligned with executors and permission checks.
- Configuration defaults are user-facing and must remain backward-compatible with existing server files.
- Stored event/player data is persistent server state. Add tolerant migrations/defaults rather than assuming a fresh install.
- Paper events run on specific threads. Keep Bukkit/Paper world and player API access on the server thread; move network or LLM work off-thread and marshal results back safely.

## Development rules

- Target the Java and Paper versions declared by Gradle and the README.
- Preserve MiniMessage formatting and written-book size/page constraints.
- LLM generation is optional. Template-only operation must remain functional, deterministic, and safe when the provider fails.
- Avoid logging private messages, credentials, or full generated prompts.
- Keep listeners lightweight and bounded; high-frequency block/movement events must not perform synchronous I/O.

## Validation

Run `./gradlew build` and the repository's tests. For behavior changes, exercise enable/disable, reload, event capture, pruning, schedule rollover, newspaper generation, and clean shutdown on a test Paper server. Inspect the built JAR under `build/libs/` and keep commits focused.
