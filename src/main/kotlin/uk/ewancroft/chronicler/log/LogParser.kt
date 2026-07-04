package uk.ewancroft.chronicler.log

import uk.ewancroft.chronicler.config.TrackingConfig
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventType
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Logger
import java.util.zip.GZIPInputStream

/**
 * Parses Minecraft server logs to extract historical events for issue #0 backfill.
 *
 * Scans the server's `logs/` directory for `latest.log` and rotated `*.log.gz` files,
 * extracting player joins, leaves, deaths, advancements, and chat messages.
 *
 * Log line format: `[HH:MM:SS] [thread/level]: message`
 * Rotated log filenames: `YYYY-MM-DD-N.log.gz`
 */
class LogParser(
    private val logsDir: Path,
    private val tracking: TrackingConfig,
    private val logger: Logger,
) {

    companion object {
        // [HH:MM:SS] [thread/level]: message
        private val LOG_LINE = Regex("^\\[(\\d{2}:\\d{2}:\\d{2})] \\[[^]]+]: (.*)$")

        // Player joined the game
        private val JOIN = Regex("^(\\w+) joined the game$")

        // Player[/IP:port] logged in with entity id X at ...
        private val LOGIN = Regex("^(\\w+)\\[/[^]]+] logged in with entity id")

        // Player left the game
        private val QUIT = Regex("^(\\w+) left the game$")

        // Player has made the advancement [Name]
        // Player has completed the challenge [Name]
        // Player has reached the goal [Name]
        private val ADVANCEMENT = Regex("^(\\w+) has (?:made the advancement|completed the challenge|reached the goal) \\[(.+)]$")

        // <Player> message
        private val CHAT = Regex("^<(\\w+)> (.*)$")

        // Death message patterns — Minecraft uses a fixed set of death message templates.
        // Each starts with the player name followed by a known verb phrase.
        private val DEATH_PATTERNS = listOf(
            Regex("^(\\w+) was (?:slain|shot|blown up|pummeled|fireballed|killed|squashed|skewered|impaled|frozen|poked|stung|sniped|roasted|prickled) .+"),
            Regex("^(\\w+) (?:fell from|fell off|fell while|hit the ground).+"),
            Regex("^(\\w+) drowned"),
            Regex("^(\\w+) suffocated"),
            Regex("^(\\w+) starved to death"),
            Regex("^(\\w+) died"),
            Regex("^(\\w+) tried to swim in lava"),
            Regex("^(\\w+) went up in flames"),
            Regex("^(\\w+) burned to death"),
            Regex("^(\\w+) was struck by lightning"),
            Regex("^(\\w+) froze to death"),
            Regex("^(\\w+) walked into a cactus"),
            Regex("^(\\w+) withered away"),
            Regex("^(\\w+) experienced kinetic energy"),
            Regex("^(\\w+) fell out of the world"),
            Regex("^(\\w+) was killed by magic"),
        )

        // Non-player killers that appear after "by" in death messages
        private val NON_PLAYER_KILLERS = setOf(
            "arrow", "magic", "fireball", "intentional game design",
            "a falling anvil", "a falling block", "a falling stalactite",
            "sweet berry bush", "falling stalactite",
        )
    }

    /**
     * Parse all log files and return a list of ChronicleEvents.
     *
     * @param maxLogFiles Maximum number of rotated log files to parse (default 100).
     * @return Events extracted from logs, sorted by timestamp ascending.
     */
    fun parse(maxLogFiles: Int = 100): List<ChronicleEvent> {
        if (!Files.isDirectory(logsDir)) {
            logger.info("Logs directory not found at $logsDir — skipping log backfill.")
            return emptyList()
        }

        val events = mutableListOf<ChronicleEvent>()
        val knownPlayers = mutableSetOf<String>()

        // Parse rotated logs first (oldest first), then latest.log
        val rotatedLogs = listRotatedLogs(maxLogFiles)
        for (logFile in rotatedLogs) {
            val (fileEvents, filePlayers) = parseGzLog(logFile)
            events.addAll(fileEvents)
            knownPlayers.addAll(filePlayers)
        }

        val latestLog = logsDir.resolve("latest.log")
        if (Files.exists(latestLog)) {
            val (fileEvents, filePlayers) = parsePlainLog(latestLog)
            events.addAll(fileEvents)
            knownPlayers.addAll(filePlayers)
        }

        // Second pass: classify deaths as PVP_KILL where the killer is a known player
        val classified = events.map { event ->
            if (event.type == EventType.DEATH) {
                val killer = event.details["killer"]
                if (killer != null && killer != "environment" && killer in knownPlayers) {
                    event.copy(type = EventType.PVP_KILL)
                } else {
                    event
                }
            } else {
                event
            }
        }

        logger.info("Parsed ${classified.size} events from ${rotatedLogs.size + if (Files.exists(latestLog)) 1 else 0} log file(s).")
        return classified.sortedBy { it.timestamp }
    }

    private fun listRotatedLogs(maxLogFiles: Int): List<Path> {
        return Files.list(logsDir)
            .filter { it.fileName.toString().endsWith(".log.gz") }
            .sorted()
            .limit(maxLogFiles.toLong())
            .toList()
    }

    private fun parseGzLog(file: Path): Pair<List<ChronicleEvent>, Set<String>> {
        val filename = file.fileName.toString()
        val dateMatch = Regex("(\\d{4}-\\d{2}-\\d{2})").find(filename)
        val date = dateMatch?.value ?: run {
            logger.warning("Could not extract date from ${file.fileName} — skipping.")
            return emptyList<ChronicleEvent>() to emptySet()
        }

        return try {
            val events = mutableListOf<ChronicleEvent>()
            val players = mutableSetOf<String>()
            GZIPInputStream(Files.newInputStream(file)).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parsed = parseLine(line, date) ?: continue
                    events.addAll(parsed)
                    parsed.filter { it.type == EventType.PLAYER_JOIN || it.type == EventType.PLAYER_LEAVE }
                        .forEach { players.add(it.playerName) }
                }
            }
            events to players
        } catch (e: Exception) {
            logger.warning("Failed to parse ${file.fileName}: ${e.message}")
            emptyList<ChronicleEvent>() to emptySet()
        }
    }

    private fun parsePlainLog(file: Path): Pair<List<ChronicleEvent>, Set<String>> {
        val date = LocalDate.ofInstant(
            Files.getLastModifiedTime(file).toInstant(),
            ZoneId.systemDefault(),
        ).toString()

        return try {
            val events = mutableListOf<ChronicleEvent>()
            val players = mutableSetOf<String>()
            Files.readAllLines(file).forEach { line ->
                val parsed = parseLine(line, date) ?: return@forEach
                events.addAll(parsed)
                parsed.filter { it.type == EventType.PLAYER_JOIN || it.type == EventType.PLAYER_LEAVE }
                    .forEach { players.add(it.playerName) }
            }
            events to players
        } catch (e: Exception) {
            logger.warning("Failed to parse latest.log: ${e.message}")
            emptyList<ChronicleEvent>() to emptySet()
        }
    }

    private fun parseLine(line: String, date: String): List<ChronicleEvent>? {
        val match = LOG_LINE.matchEntire(line) ?: return null
        val time = match.groupValues[1]
        val message = match.groupValues[2]

        val timestamp = parseTimestamp(date, time)
        val events = mutableListOf<ChronicleEvent>()

        // Player join
        if (tracking.social) {
            JOIN.find(message)?.let { m ->
                events.add(ChronicleEvent(
                    type = EventType.PLAYER_JOIN,
                    timestamp = timestamp,
                    playerName = m.groupValues[1],
                    playerUuid = "",
                    world = "server",
                ))
            }
        }

        // Player quit
        if (tracking.social) {
            QUIT.find(message)?.let { m ->
                events.add(ChronicleEvent(
                    type = EventType.PLAYER_LEAVE,
                    timestamp = timestamp,
                    playerName = m.groupValues[1],
                    playerUuid = "",
                    world = "server",
                ))
            }
        }

        // Advancement
        if (tracking.advancements) {
            ADVANCEMENT.find(message)?.let { m ->
                val name = m.groupValues[2]
                events.add(ChronicleEvent(
                    type = EventType.ADVANCEMENT,
                    timestamp = timestamp,
                    playerName = m.groupValues[1],
                    playerUuid = "",
                    world = "server",
                    details = mapOf(
                        "advancement" to name,
                        "displayName" to name,
                    ),
                ))
            }
        }

        // Chat
        if (tracking.chat) {
            CHAT.find(message)?.let { m ->
                events.add(ChronicleEvent(
                    type = EventType.CHAT,
                    timestamp = timestamp,
                    playerName = m.groupValues[1],
                    playerUuid = "",
                    world = "server",
                    details = mapOf("message" to m.groupValues[2].take(200)),
                ))
            }
        }

        // Death — check against known death message patterns
        if (tracking.deaths) {
            for (pattern in DEATH_PATTERNS) {
                val dm = pattern.find(message)
                if (dm != null) {
                    val playerName = dm.groupValues[1]
                    val killer = extractKiller(message)
                    events.add(ChronicleEvent(
                        type = EventType.DEATH,
                        timestamp = timestamp,
                        playerName = playerName,
                        playerUuid = "",
                        world = "server",
                        details = mapOf(
                            "message" to message,
                            "killer" to (killer ?: "environment"),
                            "killerUuid" to "",
                        ),
                    ))
                    break
                }
            }
        }

        return events.ifEmpty { null }
    }

    /**
     * Extract the killer's name from a death message like "X was slain by Y".
     * Returns null if there's no killer or the killer is not a player.
     */
    private fun extractKiller(deathMessage: String): String? {
        val byMatch = Regex(" by (.+)$").find(deathMessage) ?: return null
        val killer = byMatch.groupValues[1].trim()
        // Check if the killer looks like a player name (alphanumeric, not a known mob/cause)
        if (killer.matches(Regex("^\\w+$")) && killer.lowercase() !in NON_PLAYER_KILLERS) {
            return killer
        }
        return null
    }

    private fun parseTimestamp(date: String, time: String): Long {
        return try {
            val dateTime = LocalDateTime.parse(
                "$date $time",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            )
            dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
