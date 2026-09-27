package uk.ewancroft.chronicler.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import uk.ewancroft.chronicler.Chronicler
import uk.ewancroft.chronicler.config.Messages
import uk.ewancroft.chronicler.news.BookRenderer
import uk.ewancroft.chronicler.news.EventType
import uk.ewancroft.chronicler.tracker.SubscribeStore

class ChroniclerCommand(
    private val plugin: Chronicler,
    private val messages: Messages,
    private val subscribeStore: SubscribeStore,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        return when (args[0].lowercase()) {
            "read" -> readIssue(sender, args)
            "web" -> webUrl(sender)
            "latest" -> readLatest(sender)
            "reload" -> reloadConfig(sender)
            "status" -> showStatus(sender)
            "publish" -> publishNow(sender)
            "stats" -> showStats(sender, args)
            "subscribe" -> toggleSubscribe(sender)
            "archive" -> showArchive(sender, args)
            "editor" -> editor(sender, args)
            "diagnostics" -> diagnostics(sender)
            "pack" -> packCommand(sender, args)
            "newsstand" -> newsstandCommand(sender, args)
            "write", "poll", "submissions" -> {
                val contrib = plugin.getContributionCommands()
                val rest = args.drop(1)
                when {
                    contrib == null -> sender.sendMessage(net.kyori.adventure.text.Component.text("Reader contributions are turned off."))
                    args[0].equals("write", true) -> contrib.write(sender, rest)
                    args[0].equals("poll", true) -> contrib.poll(sender, rest)
                    else -> contrib.submissions(sender, rest)
                }
                true
            }
            "test" -> runTestCommand(sender, args)
            "help" -> { sendHelp(sender); true }
            else -> { sendHelp(sender); true }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            val cmds = listOf("read", "web", "latest", "reload", "status", "publish", "stats", "subscribe", "archive", "editor", "diagnostics", "pack", "newsstand", "write", "poll", "submissions", "test", "help")
            return cmds.filter { it.startsWith(args[0], true) }
        }
        if (args[0].lowercase() == "stats" && args.size == 2) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "archive" && args.size == 2) {
            return listOf("list", "read", "export", "import").filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "editor" && args.size == 2) {
            return listOf("create", "preview", "remove", "edit", "publish").filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "write" && args.size == 2) {
            return listOf("letter", "ad").filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "poll" && args.size == 2) {
            return listOf("vote", "create", "cancel", "results").filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "submissions" && args.size == 2) {
            return listOf("approve", "reject").filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "newsstand" && args.size == 2) {
            return listOf("create", "remove", "list").filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "pack" && args.size == 2) {
            return listOf("status", "export").filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "test" && args.size == 2) {
            return listOf("event", "events", "preview").filter { it.startsWith(args[1], true) }
        }
        if (args[0].lowercase() == "test" && args.getOrNull(1)?.equals("event", true) == true && args.size == 3) {
            return EventType.entries.map { it.name.lowercase() }.filter { it.startsWith(args[2], true) }
        }
        return emptyList()
    }

    private fun runTestCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("chronicler.admin")) {
            sender.sendMessage(messages.noPermission())
            return true
        }
        val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        when (args.getOrNull(1)?.lowercase()) {
            "event" -> {
                val type = args.getOrNull(2)?.let { value ->
                    EventType.entries.firstOrNull { it.name.equals(value, true) }
                }
                if (type == null) {
                    sender.sendMessage(mm.deserialize("<yellow>Usage: /chronicler test event <type></yellow>"))
                    return true
                }
                val event = plugin.recordTestEvent(type, sender)
                if (event == null) sender.sendMessage(messages.pluginNotReady())
                else sender.sendMessage(mm.deserialize("<green>Recorded test event <white>${type.name}</white>.</green>"))
            }
            "events" -> {
                val store = plugin.getEventStore() ?: run {
                    sender.sendMessage(messages.pluginNotReady())
                    return true
                }
                val limit = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 20) ?: 10
                val events = store.allEvents().takeLast(limit).reversed()
                sender.sendMessage(mm.deserialize("<gold>Buffered events: <white>${store.allEvents().size}</white></gold>"))
                events.forEach { event ->
                    sender.sendMessage(mm.deserialize(" <gray>${event.type.name} — <white>${event.playerName}</white> (${event.world})</gray>"))
                    if (event.details.isNotEmpty()) {
                        val summary = event.details.entries.joinToString(", ") { (k, v) -> "$k=${v.take(60)}" }.take(200)
                        sender.sendMessage(net.kyori.adventure.text.Component.text("   $summary", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
                    }
                }
            }
            "preview" -> {
                val started = plugin.previewNextIssue { issue ->
                    if (issue == null) sender.sendMessage(mm.deserialize("<red>Preview generation failed.</red>"))
                    else {
                        val stories = issue.sections.sumOf { it.stories.size }
                        sender.sendMessage(mm.deserialize("<green>Previewed issue <white>#${issue.issueNumber}</white>: <white>${issue.sections.size}</white> sections, <white>$stories</white> stories. No state was changed.</green>"))
                    }
                }
                sender.sendMessage(if (started) mm.deserialize("<gray>Generating preview…</gray>") else messages.pluginNotReady())
            }
            else -> sender.sendMessage(mm.deserialize("<yellow>Usage: /chronicler test event <type>, events [limit], or preview</yellow>"))
        }
        return true
    }

    /** The player behind a command, including via /execute as (a proxied sender). */
    private fun asPlayer(sender: CommandSender): Player? =
        sender as? Player ?: (sender as? org.bukkit.command.ProxiedCommandSender)?.callee as? Player

    private fun newsstandCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("chronicler.admin")) return deny(sender)
        val stands = plugin.getNewsstands() ?: return true
        val say = { text: String -> sender.sendMessage(net.kyori.adventure.text.Component.text(text)) }
        when (args.getOrNull(1)?.lowercase()) {
            "create" -> {
                val cols = args.getOrNull(2)?.toIntOrNull() ?: 2
                val rows = args.getOrNull(3)?.toIntOrNull() ?: 3
                // Console/command-block form: create <cols> <rows> <x> <y> <z> <facing> [world]
                val result = if (args.size >= 8) {
                    val (x, y, z) = listOf(args[4], args[5], args[6]).map { it.toIntOrNull() ?: return true.also { say("x y z must be whole numbers.") } }
                    val facing = runCatching { org.bukkit.block.BlockFace.valueOf(args[7].uppercase()) }.getOrNull()
                        ?: return true.also { say("Facing must be north, south, east or west.") }
                    val world = (args.getOrNull(8)?.let(plugin.server::getWorld) ?: asPlayer(sender)?.world ?: plugin.server.worlds.first())
                    stands.create(world.getBlockAt(x, y, z), facing, cols, rows)
                } else {
                    val player = asPlayer(sender) ?: return true.also { say("From the console: /chronicler newsstand create <cols> <rows> <x> <y> <z> <facing> [world]") }
                    stands.create(player, cols, rows)
                }
                say(result ?: "Newsstand placed (${cols}x$rows). It shows each new front page; right-click it to read.")
            }
            "remove" -> {
                val player = asPlayer(sender) ?: return true.also { say("Only players can remove a newsstand.") }
                say(stands.remove(player) ?: "Newsstand removed.")
            }
            "list" -> {
                val all = stands.list()
                say(if (all.isEmpty()) "No newsstands yet. Look at an item frame and run /chronicler newsstand create [cols] [rows]."
                    else all.joinToString("\n") { "${it.id}: ${it.cols}x${it.rows} in ${it.world}" })
            }
            else -> say("Usage: /chronicler newsstand create [cols] [rows] | remove | list")
        }
        return true
    }

    private fun packCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("chronicler.admin")) return deny(sender)
        val packs = plugin.getPackService()
        val built = packs?.current
        if (packs == null || !packs.enabled || built == null) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("The newspaper pack is not built (reader.mode: book, the pack is disabled, or no issue yet)."))
            return true
        }
        when (args.getOrNull(1)?.lowercase()) {
            "export" -> {
                val dir = packs.export(plugin.dataFolder.toPath().resolve("pack"))
                sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "Exported issue #${built.issueNumber}'s pack to ${dir} (zip, folder and .sha1). " +
                        "Clients stack server packs, so it can usually be offered alongside your own."))
            }
            else -> sender.sendMessage(net.kyori.adventure.text.Component.text(
                "Pack for issue #${built.issueNumber}: ${built.zip.size / 1024} KB, sha1 ${built.sha1}\n" +
                    "URL: ${packs.publicUrl() ?: "none (set web.public-url)"}\n" +
                    "Loaded by ${packs.loadedCount()} online player(s)."))
        }
        return true
    }

    private fun readIssue(sender: CommandSender, args: Array<out String>): Boolean {
        // Staff (and the console) can hand a player the paper: /chronicler read <player>
        val targetName = args.getOrNull(1)
        if (targetName != null) {
            if (!sender.hasPermission("chronicler.admin")) return deny(sender)
            val target = plugin.server.getPlayerExact(targetName)
            if (target == null) {
                sender.sendMessage(net.kyori.adventure.text.Component.text("$targetName is not online."))
                return true
            }
            plugin.giveNewspaper(target)
            sender.sendMessage(net.kyori.adventure.text.Component.text("Opened the latest issue for ${target.name}."))
            return true
        }
        if (sender !is Player) {
            sender.sendMessage(messages.playerOnly())
            return true
        }
        plugin.giveNewspaper(sender)
        return true
    }

    private fun readLatest(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage(messages.playerOnly())
            return true
        }
        plugin.giveNewspaper(sender)
        return true
    }

    private fun webUrl(sender: CommandSender): Boolean {
        val port = plugin.getWebPort()
        if (port <= 0) {
            sender.sendMessage(messages.webDisabled())
            return true
        }
        sender.sendMessage(messages.webUrl(port))
        sender.sendMessage(messages.webUrlHint())
        return true
    }

    private fun reloadConfig(sender: CommandSender): Boolean {
        if (!sender.hasPermission("chronicler.admin")) {
            sender.sendMessage(messages.noPermission())
            return true
        }
        plugin.reloadPlugin()
        sender.sendMessage(messages.reloadDone())
        return true
    }

    private fun showStatus(sender: CommandSender): Boolean {
        if (!sender.hasPermission("chronicler.admin")) {
            sender.sendMessage(messages.noPermission())
            return true
        }
        val status = plugin.getStatus()
        val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        sender.sendMessage(mm.deserialize("<gold>Chronicler <gray>v${plugin.pluginMeta.version}</gray></gold>"))
        sender.sendMessage(mm.deserialize(" <gray>Enabled: <white>${status.enabled}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Schedule: <white>${status.schedule}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Issue: <white>#${status.issueNumber}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Events stored: <white>${status.eventCount}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>LLM: <white>${if (status.llmAvailable) "<green>online</green>" else "<red>offline/disabled</red>"}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Web: <white>${if (status.webEnabled) "<green>:${status.webPort}</green>" else "<red>disabled</red>"}</white></gray>"))
        return true
    }

    private fun publishNow(sender: CommandSender): Boolean {
        if (!sender.hasPermission("chronicler.admin")) {
            sender.sendMessage(messages.noPermission())
            return true
        }
        sender.sendMessage(if (plugin.publishNow()) messages.publishDone() else net.kyori.adventure.text.Component.text("A publication is already being generated."))
        return true
    }

    private fun toggleSubscribe(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage(messages.playerOnly())
            return true
        }
        val subscribed = subscribeStore.toggle(sender.uniqueId.toString())
        sender.sendMessage(if (subscribed) messages.subscribeEnabled() else messages.subscribeDisabled())
        return true
    }

    private fun showStats(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(messages.statsUsage())
            return true
        }
        val targetName = args[1]
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            sender.sendMessage(messages.playerNotFound())
            return true
        }
        val uuid = target.uniqueId.toString()
        val store = plugin.getEventStore() ?: run {
            sender.sendMessage(messages.pluginNotReady())
            return true
        }
        val allEvents = store.allEvents()
        val playerEvents = allEvents.filter { it.playerUuid == uuid }

        val deaths = playerEvents.count { it.type.name == "DEATH" || it.type.name == "PVP_KILL" }
        val kills = playerEvents.count { it.type.name == "KILL" }
        val blocksPlaced = playerEvents.count { it.type.name == "BLOCK_PLACE" }
        val blocksBroken = playerEvents.count { it.type.name == "BLOCK_BREAK" }
        val biomes = playerEvents.filter { it.type.name == "BIOME_DISCOVERY" }.mapNotNull { it.details["biome"] }.distinct()
        val advancements = playerEvents.filter { it.type.name == "ADVANCEMENT" }.mapNotNull { it.details["displayName"] ?: it.details["advancement"] }.distinct()
        val logins = playerEvents.count { it.type.name == "PLAYER_JOIN" || it.type.name == "SESSION_START" }
        val ores = playerEvents.filter { it.type.name == "ORE_DISCOVERY" }.mapNotNull { it.details["ore"] }.distinct()
        val trades = playerEvents.count { it.type.name == "TRADE" }
        val messagesSent = playerEvents.count { it.type.name == "MESSAGE_SENT" }

        val sessionStore = plugin.getSessionStore()
        val sessionData = if (sessionStore != null) sessionStore.get(uuid) else null

        val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        sender.sendMessage(mm.deserialize("<gold>Chronicler Stats — <yellow>${target.name}</yellow></gold>"))
        sender.sendMessage(mm.deserialize(" <gray>Total events: <white>${playerEvents.size}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Deaths: <white>$deaths</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Mob kills: <white>$kills</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Blocks placed: <white>$blocksPlaced</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Blocks broken: <white>$blocksBroken</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Biomes discovered: <white>${biomes.size}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Advancements: <white>${advancements.size}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Ore types discovered: <white>${ores.size}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Trades: <white>$trades</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Messages sent: <white>$messagesSent</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Logins: <white>$logins</white></gray>"))
        if (sessionData != null) {
            val minutes = sessionData.totalPlaytimeTicks / (20 * 60)
            sender.sendMessage(mm.deserialize(" <gray>Playtime: <white>${minutes / 60}h ${minutes % 60}m</white></gray>"))
            sender.sendMessage(mm.deserialize(" <gray>Sessions: <white>${sessionData.sessionCount}</white></gray>"))
            sender.sendMessage(mm.deserialize(" <gray>Login streak: <white>${sessionData.currentStreak} days</white></gray>"))
            sender.sendMessage(mm.deserialize(" <gray>Longest streak: <white>${sessionData.longestStreak} days</white></gray>"))
        }
        return true
    }

    private fun showArchive(sender: CommandSender, args: Array<out String>): Boolean {
        val archive = plugin.getArchiveStore() ?: run {
            sender.sendMessage(messages.pluginNotReady())
            return true
        }

        if (args.size >= 2 && args[1].lowercase() == "list") {
            val issues = archive.latest(20)
            if (issues.isEmpty()) {
                sender.sendMessage(messages.archiveEmpty())
                return true
            }
            val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            sender.sendMessage(mm.deserialize("<gold>Chronicler Archive — Recent Issues:</gold>"))
            for (issue in issues) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(issue.toTime))
                val stories = issue.sections.sumOf { it.stories.size }
                sender.sendMessage(mm.deserialize(" <gray>#${issue.issueNumber} <white>($date)</white> — <white>$stories stories</white></gray>"))
            }
            sender.sendMessage(mm.deserialize(" <gray>Use <green>/chronicler archive read <#></green> to view an issue.</gray>"))
            return true
        }

        if (args.size >= 3 && args[1].lowercase() == "read") {
            val issueNum = args[2].toIntOrNull()
            if (issueNum == null) {
                sender.sendMessage(messages.archiveUsage())
                return true
            }
            if (sender !is Player) {
                sender.sendMessage(messages.playerOnly())
                return true
            }
            val newspaper = archive.getIssue(issueNum)
            if (newspaper == null) {
                sender.sendMessage(messages.archiveNotFound(issueNum))
                return true
            }
            val cfg = plugin.getNewspaperConfig()
            if (cfg != null) {
                val renderer = BookRenderer(cfg)
                val book = renderer.renderToBook(newspaper)
                if (sender.inventory.firstEmpty() == -1) {
                    sender.world.dropItem(sender.location, book)
                    sender.sendMessage(messages.archiveDropped(issueNum))
                } else {
                    sender.inventory.addItem(book)
                    sender.sendMessage(messages.archiveHere(issueNum))
                }
            }
            return true
        }

        if (args.size >= 3 && args[1].lowercase() == "export") {
            if (!sender.hasPermission("chronicler.admin")) return deny(sender)
            val issue = args[2].toIntOrNull() ?: return true.also { sender.sendMessage(messages.archiveUsage()) }
            val target = plugin.dataFolder.toPath().resolve("exports/issue-$issue.json")
            val exported = runCatching { archive.exportIssue(issue, target) }.getOrDefault(false)
            sender.sendMessage(net.kyori.adventure.text.Component.text(if (exported) "Exported issue #$issue to $target" else "Issue #$issue was not found."))
            return true
        }

        if (args.size >= 3 && args[1].lowercase() == "import") {
            if (!sender.hasPermission("chronicler.admin")) return deny(sender)
            val safeName = java.nio.file.Path.of(args[2]).fileName.toString()
            val source = plugin.dataFolder.toPath().resolve("imports").resolve(safeName)
            val imported = runCatching { archive.importIssue(source) }.getOrNull()
            sender.sendMessage(net.kyori.adventure.text.Component.text(imported?.let { "Imported issue #${it.issueNumber}." } ?: "Import failed; place a valid archive JSON in ${source.parent}."))
            return true
        }

        sender.sendMessage(messages.archiveUsage())
        return true
    }

    private fun editor(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("chronicler.admin")) return deny(sender)
        val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        when (args.getOrNull(1)?.lowercase()) {
            "create" -> {
                val started = plugin.createDraft { draft ->
                    sender.sendMessage(mm.deserialize(draft?.let { "<green>Created draft issue #${it.issueNumber}.</green>" } ?: "<red>Draft generation failed.</red>"))
                }
                sender.sendMessage(mm.deserialize(if (started) "<gray>Generating draft…</gray>" else "<yellow>Generation is already in progress or the plugin is not ready.</yellow>"))
            }
            "preview" -> {
                val draft = plugin.getDraft()
                if (draft == null) sender.sendMessage(mm.deserialize("<yellow>No draft exists.</yellow>"))
                else draft.sections.forEachIndexed { sectionIndex, section ->
                    sender.sendMessage(mm.deserialize("<gold>[$sectionIndex] ${section.title}</gold>"))
                    section.stories.forEachIndexed { storyIndex, story -> sender.sendMessage(mm.deserialize(" <gray>[$storyIndex] <white>${story.headline}</white></gray>")) }
                }
            }
            "remove" -> {
                val ok = plugin.removeDraftStory(args.getOrNull(2)?.toIntOrNull() ?: -1, args.getOrNull(3)?.toIntOrNull() ?: -1)
                sender.sendMessage(mm.deserialize(if (ok) "<green>Story removed.</green>" else "<red>Invalid draft story index.</red>"))
            }
            "edit" -> {
                val section = args.getOrNull(2)?.toIntOrNull() ?: -1
                val story = args.getOrNull(3)?.toIntOrNull() ?: -1
                val field = args.getOrNull(4)?.lowercase()
                val value = args.drop(5).joinToString(" ")
                val ok = when (field) {
                    "headline" -> plugin.editDraftStory(section, story, value, null)
                    "body" -> plugin.editDraftStory(section, story, null, value)
                    else -> false
                }
                sender.sendMessage(mm.deserialize(if (ok) "<green>Story updated.</green>" else "<red>Usage: /chronicler editor edit <section> <story> headline|body <text></red>"))
            }
            "publish" -> sender.sendMessage(mm.deserialize(if (plugin.publishDraft()) "<green>Draft published.</green>" else "<yellow>No draft exists.</yellow>"))
            else -> sender.sendMessage(mm.deserialize("<yellow>Usage: /chronicler editor create|preview|remove|edit|publish</yellow>"))
        }
        return true
    }

    private fun diagnostics(sender: CommandSender): Boolean {
        if (!sender.hasPermission("chronicler.admin")) return deny(sender)
        val status = plugin.getStatus()
        val archiveCount = plugin.getArchiveStore()?.getAll()?.size ?: 0
        val draft = plugin.getDraft()
        val dataWritable = java.nio.file.Files.isWritable(plugin.dataFolder.toPath())
        val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        sender.sendMessage(mm.deserialize("<gold>Chronicler Diagnostics</gold>"))
        sender.sendMessage(mm.deserialize(" <gray>Version: <white>${plugin.pluginMeta.version}</white>; events: <white>${status.eventCount}</white>; archives: <white>$archiveCount</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>LLM: <white>${status.llmAvailable}</white>; web: <white>${status.webEnabled}:${status.webPort}</white>; data writable: <white>$dataWritable</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Draft: <white>${draft?.let { "#${it.issueNumber}, ${it.sections.sumOf { section -> section.stories.size }} stories" } ?: "none"}</white></gray>"))
        sender.sendMessage(mm.deserialize(" <gray>Updater: <white>${uk.ewancroft.chronicler.util.UpdateChecker.status()}</white></gray>"))
        return true
    }

    private fun deny(sender: CommandSender): Boolean {
        sender.sendMessage(messages.noPermission())
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(messages.helpHeader())
        sender.sendMessage(messages.helpRead())
        sender.sendMessage(messages.helpWeb())
        sender.sendMessage(messages.helpStats())
        sender.sendMessage(messages.helpSubscribe())
        sender.sendMessage(messages.helpArchiveList())
        sender.sendMessage(messages.helpArchiveRead())
        if (sender.hasPermission("chronicler.admin")) {
            sender.sendMessage(messages.helpStatus())
            sender.sendMessage(messages.helpReload())
            sender.sendMessage(messages.helpPublish())
            val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            sender.sendMessage(mm.deserialize(" <green>/chronicler test</green> <gray>— Event and issue diagnostics</gray>"))
            sender.sendMessage(mm.deserialize(" <green>/chronicler editor</green> <gray>— Review and publish draft issues</gray>"))
            sender.sendMessage(mm.deserialize(" <green>/chronicler diagnostics</green> <gray>— Check subsystem health</gray>"))
        }
    }
}
