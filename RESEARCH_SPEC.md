# Chronicler Spec — Event Coverage + Opt-Out + Paper Setup

## Event imports (ActivityTracker / tracker package)
- `org.bukkit.event.block.SignChangeEvent`
- `org.bukkit.event.player.PlayerTeleportEvent`
- `org.bukkit.event.entity.ProjectileLaunchEvent`
(existing in WorldTracker/CombatTracker/PlayerActionTracker; add to ActivityTracker)

## Delivery / state APIs (opt-out)
- `publish-state.json` — delivery-state file; skip publish when user opted out / command-dismissed
- Subscription state (per-player; command dismissal writes skip flag)
- `LlmProvider.isAvailable()` — guard LLM delivery branch; skip if unavailable + opt-out
- Command dismissal handler writes to subscription/state; delivery loop reads before sending

## Local Paper server setup (sequential, no parallel spawn)
1. Download Paper jar to `build/paper/` (local only)
2. `java -jar paper-xxx.jar nogui` from repo root or dedicated dir
3. Plugin build: `./gradlew build` → jar to `plugins/`
4. Test: join localhost, trigger sign/teleport/projectile; verify `publish-state.json` skips when dismissed

No source edited; server not started. Author: Ruaraidh-style brief.
