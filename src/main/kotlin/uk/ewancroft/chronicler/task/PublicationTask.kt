package uk.ewancroft.chronicler.task

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import uk.ewancroft.chronicler.config.Messages
import uk.ewancroft.chronicler.config.PluginConfig
import uk.ewancroft.chronicler.config.ScheduleBase
import uk.ewancroft.chronicler.config.publicationIntervalTicks
import uk.ewancroft.chronicler.log.LogParser
import uk.ewancroft.chronicler.news.ArchiveStore
import uk.ewancroft.chronicler.news.BookRenderer
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.Newspaper
import uk.ewancroft.chronicler.news.NewspaperGenerator
import uk.ewancroft.chronicler.news.WebRenderer
import uk.ewancroft.chronicler.tracker.SubscribeStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Calendar
import java.util.logging.Level
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean

class PublicationTask(
    private val plugin: JavaPlugin,
    private val config: PluginConfig,
    private val store: EventStore,
    private val subscribeStore: SubscribeStore,
    private val messages: Messages,
    private val generator: NewspaperGenerator,
    private val bookRenderer: BookRenderer,
    private val webRenderer: WebRenderer?,
    private val archiveStore: ArchiveStore?,
    private val logger: Logger,
    private val activationTime: Long,
    private val logsDir: Path? = null,
) {

    private var issueNumber = 0
    private var lastPublishTime = 0L
    private var lastPublishGameTime = 0L
    private var latestBook: ItemStack? = null
    private var latestNewspaper: Newspaper? = null
    private var draftNewspaper: Newspaper? = null
    private var scheduledTask: ScheduledTask? = null
    private val stateFile: Path = plugin.dataFolder.toPath().resolve("publish-state.json")
    private val draftFile: Path = plugin.dataFolder.toPath().resolve("draft-issue.json")
    private val deliveredPlayers = mutableSetOf<String>()
    private val generationInProgress = AtomicBoolean(false)

    fun start() {
        loadState()
        loadDraft()
        restoreLatestIssue()

        if (issueNumber == 0 && lastPublishTime == 0L) {
            // First run — backfill from server logs if enabled
            if (config.backfillEnabled && logsDir != null) {
                val parser = LogParser(logsDir, config.tracking, logger)
                val parsedEvents = parser.parse(config.backfillMaxLogFiles)
                if (parsedEvents.isNotEmpty()) {
                    store.recordAll(parsedEvents)
                    logger.info("Backfilled ${parsedEvents.size} events from server logs for issue #0.")
                }
            }

            val backfilledEvents = store.allEvents().count { it.timestamp <= activationTime }
            if (backfilledEvents > 0) {
                logger.info("No prior issues found — publishing issue #0 with $backfilledEvents events through plugin activation.")
                doPublish(isIssueZero = true, cutoffTime = activationTime)
            }
        }

        scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { _ -> checkAndPublish() },
            20L,
            20L,
        )

        logger.info("Publication task scheduled (${config.schedule.lowercase()})")
    }

    private fun restoreLatestIssue() {
        val newspaper = archiveStore?.latest(1)?.firstOrNull() ?: return
        try {
            latestNewspaper = newspaper
            latestBook = bookRenderer.renderToBook(newspaper)
            webRenderer?.renderAndServe(newspaper)
            logger.info("Restored issue #${newspaper.issueNumber} from the archive (${newspaper.sections.sumOf { it.stories.size }} stories).")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to restore archived issue #${newspaper.issueNumber}.", e)
        }
    }

    fun stop() {
        logger.info("Stopping publication task at issue #$issueNumber.")
        scheduledTask?.cancel()
        scheduledTask = null
        saveState()
    }

    fun publishNow(): Boolean {
        return if (config.reviewRequired) createDraftAsync() else doPublish()
    }

    fun createDraftAsync(onComplete: ((Newspaper?) -> Unit)? = null): Boolean {
        if (!generationInProgress.compareAndSet(false, true)) return false
        val number = issueNumber + 1
        val fromTime = lastPublishTime
        val toTime = System.currentTimeMillis()
        Bukkit.getAsyncScheduler().runNow(plugin) {
            val result = runCatching { generator.generate(number, fromTime, toTime) }
            Bukkit.getGlobalRegionScheduler().run(plugin) {
                val newspaper = result.getOrElse { error ->
                    logger.log(Level.SEVERE, "Failed to create draft issue #$number.", error)
                    null
                }
                if (newspaper != null) {
                    draftNewspaper = newspaper
                    saveDraft()
                    logger.info("Created draft issue #$number with ${newspaper.sections.sumOf { section -> section.stories.size }} stories; awaiting editor approval.")
                }
                generationInProgress.set(false)
                onComplete?.invoke(newspaper)
            }
        }
        return true
    }

    fun getDraft(): Newspaper? = draftNewspaper

    fun removeDraftStory(sectionIndex: Int, storyIndex: Int): Boolean {
        val draft = draftNewspaper ?: return false
        val section = draft.sections.getOrNull(sectionIndex) ?: return false
        if (storyIndex !in section.stories.indices) return false
        val sections = draft.sections.toMutableList()
        sections[sectionIndex] = section.copy(stories = section.stories.filterIndexed { index, _ -> index != storyIndex })
        draftNewspaper = draft.copy(sections = sections.filter { it.stories.isNotEmpty() })
        saveDraft()
        return true
    }

    fun editDraftStory(sectionIndex: Int, storyIndex: Int, headline: String? = null, body: String? = null): Boolean {
        val draft = draftNewspaper ?: return false
        val section = draft.sections.getOrNull(sectionIndex) ?: return false
        val story = section.stories.getOrNull(storyIndex) ?: return false
        val stories = section.stories.toMutableList()
        stories[storyIndex] = story.copy(
            headline = headline?.trim()?.take(60)?.ifBlank { story.headline } ?: story.headline,
            body = body?.trim()?.ifBlank { story.body } ?: story.body,
        )
        val sections = draft.sections.toMutableList()
        sections[sectionIndex] = section.copy(stories = stories)
        draftNewspaper = draft.copy(sections = sections)
        saveDraft()
        return true
    }

    fun publishDraft(): Boolean {
        val draft = draftNewspaper ?: return false
        publishNewspaper(draft, draft.toTime, false)
        draftNewspaper = null
        Files.deleteIfExists(draftFile)
        return true
    }

    fun getIssueNumber(): Int = issueNumber
    fun getLastPublishTime(): Long = lastPublishTime
    fun getLatestBook(): ItemStack? = latestBook?.clone()
    fun getLatestNewspaper(): Newspaper? = latestNewspaper

    fun deliverToPlayer(player: org.bukkit.entity.Player) {
        val uuid = player.uniqueId.toString()
        if (uuid in deliveredPlayers) return
        if (latestBook == null) return
        if (!subscribeStore.isSubscribed(uuid)) return
        giveBook(player, issueNumber)
    }

    private fun giveBook(player: org.bukkit.entity.Player, number: Int) {
        val book = latestBook?.clone() ?: return
        val uuid = player.uniqueId.toString()
        val remaining = player.inventory.addItem(book)
        if (remaining.isEmpty()) {
            player.sendMessage(messages.deliveryInventory(config.newspaper.title, number))
        } else {
            player.world.dropItem(player.location, book)
            player.sendMessage(messages.deliveryDrop(config.newspaper.title, number))
        }
        deliveredPlayers.add(uuid)
    }

    private fun checkAndPublish() {
        if (!config.enabled) return

        val now = System.currentTimeMillis()
        val shouldPublish = when (config.scheduleBase) {
            ScheduleBase.REAL_TIME -> shouldPublishRealTime(now)
            ScheduleBase.IN_GAME -> shouldPublishInGame()
        }

        if (shouldPublish) {
            doPublish()
        }
    }

    private fun shouldPublishRealTime(now: Long): Boolean {
        val calendar = Calendar.getInstance()
        val shouldPublish = when (config.schedule.uppercase()) {
            "DAILY" -> true
            "WEEKLY" -> calendar.get(Calendar.DAY_OF_WEEK) == dayOfWeekToCalendar(config.publishDay)
            "BIWEEKLY" -> calendar.get(Calendar.DAY_OF_WEEK) == dayOfWeekToCalendar(config.publishDay) &&
                (calendar.get(Calendar.WEEK_OF_YEAR) % 2 == 0)
            "MONTHLY" -> calendar.get(Calendar.DAY_OF_MONTH) == config.publishDay.coerceIn(1, 28)
            "HOURLY" -> true
            else -> true
        }

        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val correctHour = config.publishHour
        return shouldPublish && hour == correctHour && (now - lastPublishTime) > 3600_000L
    }

    private fun shouldPublishInGame(): Boolean {
        val interval = publicationIntervalTicks(config.schedule, config.scheduleBase)
        if (interval <= 0L) return false
        if (lastPublishGameTime <= 0L) {
            lastPublishGameTime = currentGameTime()
            return false
        }
        val currentGameTime = currentGameTime()
        return currentGameTime > 0L && (currentGameTime - lastPublishGameTime) >= interval
    }

    private fun doPublish(isIssueZero: Boolean = false, cutoffTime: Long = System.currentTimeMillis()): Boolean {
        if (!generationInProgress.compareAndSet(false, true)) return false
        val toTime = cutoffTime
        val fromTime = if (isIssueZero) 0L else lastPublishTime
        val number = if (isIssueZero) 0 else issueNumber + 1
        val eventCount = store.eventsSince(fromTime).count { it.timestamp <= toTime }
        val startedAt = System.currentTimeMillis()

        logger.info("Starting publication of issue #$number with $eventCount events ($fromTime to $toTime).")
        Bukkit.getAsyncScheduler().runNow(plugin) {
            val result = runCatching { generator.generate(number, fromTime, toTime) }
            Bukkit.getGlobalRegionScheduler().run(plugin) {
                try {
                    result.fold(
                        onSuccess = { publishNewspaper(it, toTime, isIssueZero, startedAt, eventCount) },
                        onFailure = { logger.log(Level.SEVERE, "Failed to publish issue #$number after ${System.currentTimeMillis() - startedAt}ms.", it) },
                    )
                } finally {
                    generationInProgress.set(false)
                }
            }
        }
        return true
    }

    private fun publishNewspaper(
        newspaper: Newspaper,
        toTime: Long,
        isIssueZero: Boolean,
        startedAt: Long = System.currentTimeMillis(),
        eventCount: Int = store.eventsSince(lastPublishTime).count { it.timestamp <= toTime },
    ) {
        val number = newspaper.issueNumber
        try {
            val book = bookRenderer.renderToBook(newspaper)
            val storyCount = newspaper.sections.sumOf { it.stories.size }
            logger.info("Rendered issue #$number: ${newspaper.sections.size} sections, $storyCount stories, ${book.itemMeta.let { it as org.bukkit.inventory.meta.BookMeta }.pageCount} book pages.")

            latestNewspaper = newspaper
            latestBook = book
            archiveStore?.archive(newspaper)
            if (archiveStore != null) logger.info("Archived issue #$number.")
            deliveredPlayers.clear()

            var delivered = 0
            Bukkit.getOnlinePlayers().forEach { player ->
                if (subscribeStore.isSubscribed(player.uniqueId.toString())) {
                    giveBook(player, number)
                    delivered++
                }
            }
            logger.info("Delivered issue #$number to $delivered online subscriber(s).")

            webRenderer?.renderAndServe(newspaper)
            if (webRenderer != null) logger.info("Rendered web edition for issue #$number.")

            lastPublishTime = toTime
            lastPublishGameTime = currentGameTime()
            if (!isIssueZero) {
                issueNumber = number
            }
            store.removeThrough(toTime)
            saveState()

            logger.info("Published issue #$number in ${System.currentTimeMillis() - startedAt}ms; consumed $eventCount events.")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to publish issue #$number after ${System.currentTimeMillis() - startedAt}ms.", e)
            throw e
        }
    }

    private fun loadState() {
        try {
            if (Files.exists(stateFile)) {
                val text = stateFile.toFile().readText()
                val lines = text.lines()
                if (lines.size >= 2) {
                    issueNumber = lines[0].toIntOrNull() ?: 0
                    lastPublishTime = lines[1].toLongOrNull() ?: System.currentTimeMillis()
                }
                if (lines.size >= 3) {
                    lastPublishGameTime = lines[2].toLongOrNull() ?: currentGameTime()
                } else if (config.scheduleBase == ScheduleBase.IN_GAME) {
                    lastPublishGameTime = currentGameTime()
                }
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to load publication state from $stateFile; starting from issue zero.", e)
            lastPublishTime = 0L
            lastPublishGameTime = 0L
        }
    }

    private fun saveState() {
        try {
            Files.createDirectories(stateFile.parent)
            Files.writeString(stateFile, "$issueNumber\n$lastPublishTime\n$lastPublishGameTime")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to save publication state to $stateFile.", e)
        }
    }

    private fun loadDraft() {
        if (!Files.exists(draftFile)) return
        try {
            draftNewspaper = Json { ignoreUnknownKeys = true }.decodeFromString<Newspaper>(Files.readString(draftFile))
            logger.info("Restored draft issue #${draftNewspaper?.issueNumber} from disk.")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to restore draft from $draftFile; preserving the unreadable file for recovery.", e)
        }
    }

    private fun saveDraft() {
        val draft = draftNewspaper ?: return
        try {
            Files.createDirectories(draftFile.parent)
            val temporary = draftFile.resolveSibling("${draftFile.fileName}.tmp")
            Files.writeString(temporary, Json { prettyPrint = true }.encodeToString(draft))
            Files.move(temporary, draftFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to save draft issue #${draft.issueNumber}.", e)
        }
    }

    private fun dayOfWeekToCalendar(day: Int): Int {
        return when (day) {
            0 -> Calendar.MONDAY
            1 -> Calendar.TUESDAY
            2 -> Calendar.WEDNESDAY
            3 -> Calendar.THURSDAY
            4 -> Calendar.FRIDAY
            5 -> Calendar.SATURDAY
            6 -> Calendar.SUNDAY
            else -> Calendar.MONDAY
        }
    }

    private fun currentGameTime(): Long = Bukkit.getWorlds().firstOrNull()?.fullTime ?: 0L
}
