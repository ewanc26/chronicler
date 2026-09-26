# MCP → Chronicler Integration (local, real)
Source: /tmp/minecraft-mcp-server (v2.0.4, Mineflayer MCP, built)
Target: Chronicler /Users/ewan/Developer/Git/Chronicler

Bridge: Bot events (build/sign/teleport/combat) → Chronicler tracker/ → EventStore
Missing for full coverage: SignEvent / TeleportEvent record classes (Chronicler tracker)
Status: MCP installed but requires real Minecraft 1.21.11 server + bot config to trigger events
Action: when event classes exist, bot can trigger sign/teleport for testing; otherwise use MCP for external observation only
