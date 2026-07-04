package uk.ewancroft.chronicler.log

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.ewancroft.chronicler.config.TrackingConfig
import uk.ewancroft.chronicler.news.EventType
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogParserTest {

    @TempDir
    lateinit var tempDir: Path

    private val tracking = TrackingConfig(
        deaths = true, kills = true, pvp = true, advancements = true,
        blocks = true, exploration = true, social = true, economy = true,
        chat = true, crafting = true, fishing = true, sleep = true,
        portals = true, entities = true, explosions = true, weather = true,
        raids = true, teleport = true, consumption = true, projectiles = true,
        vehicles = true, misc = true,
    )

    private val logger = Logger.getLogger("test")

    private fun writeLog(path: Path, lines: List<String>) {
        Files.createDirectories(path.parent)
        Files.write(path, lines)
    }

    private fun writeGzLog(path: Path, lines: List<String>) {
        Files.createDirectories(path.parent)
        GZIPOutputStream(Files.newOutputStream(path)).bufferedWriter().use { writer ->
            lines.forEach { writer.write(it); writer.newLine() }
        }
    }

    @Test
    fun `parses player join`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve joined the game",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.PLAYER_JOIN, events[0].type)
        assertEquals("Steve", events[0].playerName)
    }

    @Test
    fun `parses player leave`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Alex left the game",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.PLAYER_LEAVE, events[0].type)
        assertEquals("Alex", events[0].playerName)
    }

    @Test
    fun `parses advancement`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve has made the advancement [Stone Age]",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.ADVANCEMENT, events[0].type)
        assertEquals("Steve", events[0].playerName)
        assertEquals("Stone Age", events[0].details["advancement"])
        assertEquals("Stone Age", events[0].details["displayName"])
    }

    @Test
    fun `parses challenge advancement`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve has completed the challenge [How Did We Get Here?]",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.ADVANCEMENT, events[0].type)
        assertEquals("How Did We Get Here?", events[0].details["advancement"])
    }

    @Test
    fun `parses goal advancement`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Alex has reached the goal [Monster Hunter]",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.ADVANCEMENT, events[0].type)
    }

    @Test
    fun `parses chat message`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: <Steve> Hello world!",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.CHAT, events[0].type)
        assertEquals("Steve", events[0].playerName)
        assertEquals("Hello world!", events[0].details["message"])
    }

    @Test
    fun `parses death by environment`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve was slain by Zombie",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.DEATH, events[0].type)
        assertEquals("Steve", events[0].playerName)
        assertEquals("Zombie", events[0].details["killer"])
    }

    @Test
    fun `parses death by fall`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve fell from a high place",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.DEATH, events[0].type)
        assertEquals("environment", events[0].details["killer"])
    }

    @Test
    fun `parses death by drowning`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Alex drowned",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.DEATH, events[0].type)
    }

    @Test
    fun `classifies PvP kill when killer is a known player`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve joined the game",
            "[12:00:01] [Server thread/INFO]: Alex joined the game",
            "[12:00:02] [Server thread/INFO]: Steve was slain by Alex",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        // 2 joins + 1 death = 3 events
        assertEquals(3, events.size)
        val death = events.last()
        assertEquals(EventType.PVP_KILL, death.type)
        assertEquals("Steve", death.playerName)
        assertEquals("Alex", death.details["killer"])
    }

    @Test
    fun `does not classify PvP kill when killer is not a known player`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve joined the game",
            "[12:00:01] [Server thread/INFO]: Steve was slain by Zombie",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        val death = events.last()
        assertEquals(EventType.DEATH, death.type)
        assertEquals("Zombie", death.details["killer"])
    }

    @Test
    fun `parses gz log file`() {
        writeGzLog(tempDir.resolve("2024-01-15-1.log.gz"), listOf(
            "[08:00:00] [Server thread/INFO]: Steve joined the game",
            "[08:05:00] [Server thread/INFO]: Steve was slain by Zombie",
            "[08:10:00] [Server thread/INFO]: Steve left the game",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(3, events.size)
        assertEquals(EventType.PLAYER_JOIN, events[0].type)
        assertEquals(EventType.DEATH, events[1].type)
        assertEquals(EventType.PLAYER_LEAVE, events[2].type)
    }

    @Test
    fun `parses multiple gz log files in order`() {
        writeGzLog(tempDir.resolve("2024-01-14-1.log.gz"), listOf(
            "[08:00:00] [Server thread/INFO]: Steve joined the game",
        ))
        writeGzLog(tempDir.resolve("2024-01-15-1.log.gz"), listOf(
            "[09:00:00] [Server thread/INFO]: Alex joined the game",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(2, events.size)
        assertEquals("Steve", events[0].playerName)
        assertEquals("Alex", events[1].playerName)
    }

    @Test
    fun `parses both gz and latest log`() {
        writeGzLog(tempDir.resolve("2024-01-14-1.log.gz"), listOf(
            "[08:00:00] [Server thread/INFO]: Steve joined the game",
        ))
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Alex joined the game",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(2, events.size)
        assertEquals("Steve", events[0].playerName)
        assertEquals("Alex", events[1].playerName)
    }

    @Test
    fun `ignores non-event log lines`() {
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Starting minecraft server version 1.21.4",
            "[12:00:01] [Server thread/INFO]: Done (3.5s)! For help, type \"help\"",
            "[12:00:02] [Server thread/INFO]: Stopping the server",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `returns empty when logs directory does not exist`() {
        val parser = LogParser(tempDir.resolve("nonexistent"), tracking, logger)
        val events = parser.parse()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `respects tracking config - disabled social`() {
        val noSocial = tracking.copy(social = false)
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve joined the game",
            "[12:00:01] [Server thread/INFO]: Steve left the game",
            "[12:00:02] [Server thread/INFO]: <Steve> Hello",
        ))
        val parser = LogParser(tempDir, noSocial, logger)
        val events = parser.parse()
        // Only chat should be parsed (social disabled)
        assertEquals(1, events.size)
        assertEquals(EventType.CHAT, events[0].type)
    }

    @Test
    fun `respects tracking config - disabled deaths`() {
        val noDeaths = tracking.copy(deaths = false)
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve joined the game",
            "[12:00:01] [Server thread/INFO]: Steve was slain by Zombie",
        ))
        val parser = LogParser(tempDir, noDeaths, logger)
        val events = parser.parse()
        // Only join should be parsed (deaths disabled)
        assertEquals(1, events.size)
        assertEquals(EventType.PLAYER_JOIN, events[0].type)
    }

    @Test
    fun `respects tracking config - disabled advancements`() {
        val noAdv = tracking.copy(advancements = false)
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve joined the game",
            "[12:00:01] [Server thread/INFO]: Steve has made the advancement [Stone Age]",
        ))
        val parser = LogParser(tempDir, noAdv, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.PLAYER_JOIN, events[0].type)
    }

    @Test
    fun `respects tracking config - disabled chat`() {
        val noChat = tracking.copy(chat = false)
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[12:00:00] [Server thread/INFO]: Steve joined the game",
            "[12:00:01] [Server thread/INFO]: <Steve> Hello",
        ))
        val parser = LogParser(tempDir, noChat, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        assertEquals(EventType.PLAYER_JOIN, events[0].type)
    }

    @Test
    fun `limits rotated log files`() {
        for (i in 1..5) {
            writeGzLog(tempDir.resolve("2024-01-1$i-1.log.gz"), listOf(
                "[08:00:00] [Server thread/INFO]: Player$i joined the game",
            ))
        }
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse(maxLogFiles = 3)
        // Should only parse 3 of the 5 gz files
        assertEquals(3, events.size)
    }

    @Test
    fun `events are sorted by timestamp`() {
        writeGzLog(tempDir.resolve("2024-01-14-1.log.gz"), listOf(
            "[08:00:00] [Server thread/INFO]: Steve joined the game",
        ))
        writeLog(tempDir.resolve("latest.log"), listOf(
            "[06:00:00] [Server thread/INFO]: Alex joined the game",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(2, events.size)
        // Steve (2024-01-14 08:00) should come before Alex (latest.log 06:00)
        // because the gz log date is earlier than the latest.log date
        assertTrue(events[0].timestamp <= events[1].timestamp)
    }

    @Test
    fun `parses various death messages`() {
        val deathMessages = listOf(
            "Steve was shot by Skeleton",
            "Steve was blown up by Creeper",
            "Steve fell from a high place",
            "Steve fell off a ladder",
            "Steve hit the ground too hard",
            "Steve suffocated in a wall",
            "Steve starved to death",
            "Steve tried to swim in lava",
            "Steve went up in flames",
            "Steve burned to death",
            "Steve was struck by lightning",
            "Steve froze to death",
            "Steve walked into a cactus",
            "Steve withered away",
            "Steve experienced kinetic energy",
            "Steve fell out of the world",
            "Steve was killed by magic",
            "Steve died",
        )
        val lines = deathMessages.mapIndexed { i, msg ->
            "[12:00:${i.toString().padStart(2, '0')}] [Server thread/INFO]: $msg"
        }
        writeLog(tempDir.resolve("latest.log"), lines)

        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(deathMessages.size, events.size)
        events.forEach { assertEquals(EventType.DEATH, it.type) }
    }

    @Test
    fun `extracts correct timestamp from gz log`() {
        writeGzLog(tempDir.resolve("2024-06-15-1.log.gz"), listOf(
            "[14:30:45] [Server thread/INFO]: Steve joined the game",
        ))
        val parser = LogParser(tempDir, tracking, logger)
        val events = parser.parse()
        assertEquals(1, events.size)
        // The timestamp should be for 2024-06-15 14:30:45 in the system timezone
        val expectedDate = java.time.LocalDateTime.of(2024, 6, 15, 14, 30, 45)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        assertEquals(expectedDate, events[0].timestamp)
    }
}
