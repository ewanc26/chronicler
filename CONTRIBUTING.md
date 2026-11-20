# Contributing to Chronicler

A PaperMC plugin that tracks server events and generates a dynamic in-game newspaper delivered as a written book. Thanks for wanting to help!

## Getting started

Build with the Gradle wrapper (`./gradlew build`); the plugin builds against Paper API (api-version 1.21). Testing realistically means a local Paper server with the plugin loaded.

## Notes for this project

- **Don't break the database for existing servers.** Any storage change needs a migration path for servers that already have event history.
- Privacy controls (player exclusion, redaction of private messages, coordinates; chat text never stored when chat excerpts are off) are promises, not features. A PR that weakens them won't be merged.
- The optional LLM integration must degrade cleanly to template summaries when the model is unavailable: the newspaper still comes out either way.
- New event types should come with their own section mapping and a sensible default in `config.yml`.
- Update releases verify SHA-256 on download; keep that guarantee intact in any release-tooling change.

## Before opening a PR

- `./gradlew build` passes; you loaded the built JAR on a test server and the newspaper reads correctly.
- Conventional commits; focused PRs; say how you verified (a short in-game test description goes a long way).
- AI-assisted PRs welcome; stand behind every line and run the build yourself.

## Licence

See [LICENSE](LICENSE). Contributions accepted under the same terms.