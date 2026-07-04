package uk.ewancroft.chronicler.news

import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.config.PrivacyConfig
import uk.ewancroft.chronicler.llm.ArticleResult
import uk.ewancroft.chronicler.llm.LlmProvider
import java.util.logging.Logger

class NewspaperGenerator(
    private val store: EventStore,
    private val newspaperConfig: NewspaperConfig,
    private val llmProvider: LlmProvider?,
    private val llmEnabled: Boolean,
    private val logger: Logger,
    private val llmSystemPrompt: String = "",
    private val privacyConfig: PrivacyConfig = PrivacyConfig(false, false, false, emptySet()),
) {
    private val maxStories = newspaperConfig.storiesPerSection

    fun generate(issueNumber: Int, fromTime: Long, toTime: Long): Newspaper {
        val events = store.eventsSince(fromTime)
            .asSequence()
            .filter { it.timestamp <= toTime }
            .filterNot { it.playerName.lowercase() in privacyConfig.excludedPlayers }
            .filterNot { it.type == EventType.MESSAGE_SENT && !privacyConfig.includePrivateMessages }
            .map(::redactEvent)
            .toList()
        logger.info("Generating issue #$issueNumber from ${events.size} events ($fromTime to $toTime).")
        val sections = mutableListOf<NewspaperSection>()

        sections.addAll(generateHeadlines(events))
        sections.addAll(generateObituaries(events))
        sections.addAll(generateAchievements(events))
        sections.addAll(generateHuntingGrounds(events))
        sections.addAll(generateExplorationAndBuilding(events))
        sections.addAll(generateEconomyAndTrading(events))
        sections.addAll(generateSocial(events))
        sections.addAll(generateBreakingNews(events))
        sections.addAll(generateCraftingAndEnchanting(events))
        sections.addAll(generateFarmingAndTaming(events))
        sections.addAll(generateTravelAndTransport(events))
        sections.addAll(generateWorldEvents(events))
        sections.addAll(generateCommunityLife(events))
        sections.addAll(generateAdventures(events))
        if (newspaperConfig.showStatistics) {
            sections.add(generateStatistics(events))
        }

        val order = newspaperConfig.sectionOrder.mapIndexed { index, title -> title.lowercase() to index }.toMap()
        val structuredSections = sections.map { section ->
            section.copy(stories = section.stories.map(::structureStory).sortedByDescending(::importanceScore))
        }.sortedWith(compareBy({ order[it.title.lowercase()] ?: Int.MAX_VALUE }, { it.title }))
        logger.info("Generated issue #$issueNumber with ${structuredSections.size} sections and ${structuredSections.sumOf { it.stories.size }} stories.")
        return Newspaper(issueNumber, fromTime, toTime, structuredSections)
    }

    private fun generateHeadlines(events: List<ChronicleEvent>): List<NewspaperSection> {
        val deathEvents = events.filter { it.type == EventType.DEATH }
        val pvpEvents = events.filter { it.type == EventType.PVP_KILL }
        val advEvents = events.filter { it.type == EventType.ADVANCEMENT }
        val endEvents = events.filter { it.type == EventType.END_ENTER }

        val summary = buildString {
            if (deathEvents.isNotEmpty()) {
                appendLine("Deaths (${deathEvents.size} total):")
                deathEvents.groupBy { it.details["message"] ?: "died" }.entries.take(5).forEach { (msg, evs) ->
                    appendLine("- ${evs.size}x: ${msg.take(100)} (${evs.map { it.playerName }.distinct().joinToString(", ")})")
                }
                appendLine()
            }
            if (pvpEvents.isNotEmpty()) {
                appendLine("PvP kills (${pvpEvents.size} total):")
                pvpEvents.groupBy { it.details["killer"] ?: "unknown" }.entries.take(5).forEach { (killer, evs) ->
                    appendLine("- $killer killed ${evs.size} player(s)")
                }
                appendLine()
            }
            if (advEvents.isNotEmpty()) {
                appendLine("Notable advancements (${advEvents.size} total):")
                advEvents.groupBy { it.details["displayName"] ?: "unknown" }.entries.take(3).forEach { (adv, evs) ->
                    appendLine("- \"$adv\" earned by ${evs.size} player(s)")
                }
            }
            if (endEvents.isNotEmpty()) {
                appendLine()
                appendLine("${endEvents.size} player(s) entered The End for the first time!")
            }
        }

        val stories = generateArticles("Headlines", summary, events, maxStories = 3) ?: run {
            val s = mutableListOf<Story>()
            if (deathEvents.isEmpty() && pvpEvents.isEmpty() && advEvents.isEmpty() && endEvents.isEmpty()) {
                s.add(Story("Quiet Days", "No major events to report.", emptyList(), null))
            }
            if (deathEvents.isNotEmpty()) {
                s.add(Story("Casualties", "${deathEvents.size} deaths recorded this cycle.", emptyList(), EventType.DEATH))
            }
            if (pvpEvents.isNotEmpty()) {
                val top = pvpEvents.groupBy { it.details["killer"] ?: "unknown" }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("PvP Report", "${top.key} leads with ${top.value.size} kills.", listOf(top.key), EventType.PVP_KILL))
            }
            if (endEvents.isNotEmpty()) {
                val players = endEvents.map { it.playerName }.distinct()
                s.add(Story("Into The End", "${players.size} brave explorer(s) ventured into the End.", players, EventType.END_ENTER))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Headlines", stories))
    }

    private fun generateObituaries(events: List<ChronicleEvent>): List<NewspaperSection> {
        val deathEvents = events.filter { it.type == EventType.DEATH }
        if (deathEvents.isEmpty()) return emptyList()

        val summary = buildString {
            appendLine("Obituary report — ${deathEvents.size} deaths:")
            deathEvents.groupBy { it.playerName }.entries.sortedByDescending { it.value.size }.take(5).forEach { (player, deaths) ->
                val causes = deaths.map { it.details["message"] ?: "died" }.distinct()
                appendLine("- $player: ${deaths.size} deaths (causes: ${causes.take(3).joinToString(", ")}${if (causes.size > 3) "..." else ""})")
            }
        }

        val stories = generateArticles("Obituaries", summary, deathEvents, maxStories = 5) ?: run {
            deathEvents.groupBy { it.playerName }.entries
                .sortedByDescending { it.value.size }
                .take(maxStories)
                .map { (player, deaths) ->
                    val cause = deaths.last().details["message"] ?: "died"
                    Story("$player — ${deaths.size} deaths", "Last seen: $cause", listOf(player), EventType.DEATH)
                }
        }

        return listOf(NewspaperSection("Obituaries", stories))
    }

    private fun generateAchievements(events: List<ChronicleEvent>): List<NewspaperSection> {
        val advEvents = events.filter { it.type == EventType.ADVANCEMENT }
        if (advEvents.isEmpty()) return emptyList()

        val summary = buildString {
            appendLine("Achievements unlocked (${advEvents.size} total):")
            advEvents.groupBy { it.details["displayName"] ?: it.details["advancement"] ?: "unknown" }.entries.take(5).forEach { (adv, evs) ->
                appendLine("- \"$adv\" — ${evs.map { it.playerName }.distinct().joinToString(", ")}")
            }
        }

        val stories = generateArticles("Achievements", summary, advEvents, maxStories = 5) ?: run {
            advEvents.distinctBy { it.details["advancement"] }.take(maxStories).map { event ->
                val displayName = event.details["displayName"] ?: event.details["advancement"] ?: "unknown"
                val count = advEvents.count { it.details["advancement"] == event.details["advancement"] }
                Story(
                    if (displayName.length > 40) displayName.take(37) + "..." else displayName,
                    "Achieved by $count player(s). First: ${event.playerName}",
                    listOf(event.playerName),
                    EventType.ADVANCEMENT,
                )
            }
        }

        return listOf(NewspaperSection("Achievements", stories))
    }

    private fun generateHuntingGrounds(events: List<ChronicleEvent>): List<NewspaperSection> {
        val pvpEvents = events.filter { it.type == EventType.PVP_KILL }
        val killEvents = events.filter { it.type == EventType.KILL }
        if (pvpEvents.isEmpty() && killEvents.isEmpty()) return emptyList()

        val summary = buildString {
            if (killEvents.isNotEmpty()) {
                appendLine("Mob kills (${killEvents.size} total):")
                killEvents.groupBy { it.playerName }.entries.sortedByDescending { it.value.size }.take(3).forEach { (player, kills) ->
                    val fav = kills.groupBy { it.details["entity"] ?: "unknown" }.maxByOrNull { it.value.size }
                    appendLine("- $player: ${kills.size} kills (favorite prey: ${fav?.key ?: "various"})")
                }
                appendLine()
            }
            if (pvpEvents.isNotEmpty()) {
                appendLine("Player kills (${pvpEvents.size} total):")
                pvpEvents.groupBy { it.details["killer"] ?: "unknown" }.entries.sortedByDescending { it.value.size }.take(3).forEach { (killer, kills) ->
                    appendLine("- $killer: ${kills.size} player kills")
                }
            }
        }

        val stories = generateArticles("Hunting Grounds", summary, killEvents + pvpEvents, maxStories = 5) ?: run {
            val s = mutableListOf<Story>()
            val byKiller = killEvents.groupBy { it.playerName }
            val topHunter = byKiller.maxByOrNull { it.value.size }
            if (topHunter != null) {
                val fav = topHunter.value.groupBy { it.details["entity"] ?: "unknown" }.maxByOrNull { it.value.size }
                s.add(Story("Top Hunter: ${topHunter.key}", "${topHunter.value.size} kills this cycle. Favorite: ${fav?.key ?: "various"}", listOf(topHunter.key), EventType.KILL))
            }
            if (pvpEvents.isNotEmpty()) {
                val top = pvpEvents.groupBy { it.details["killer"] ?: "unknown" }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("PvP Leader: ${top.key}", "${top.value.size} player kills", listOf(top.key), EventType.PVP_KILL))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Hunting Grounds", stories))
    }

    private fun generateExplorationAndBuilding(events: List<ChronicleEvent>): List<NewspaperSection> {
        val biomeEvents = events.filter { it.type == EventType.BIOME_DISCOVERY }
        val placeEvents = events.filter { it.type == EventType.BLOCK_PLACE }
        val breakEvents = events.filter { it.type == EventType.BLOCK_BREAK }
        val oreEvents = events.filter { it.type == EventType.ORE_DISCOVERY }
        if (biomeEvents.isEmpty() && placeEvents.isEmpty() && oreEvents.isEmpty()) return emptyList()

        val summary = buildString {
            if (biomeEvents.isNotEmpty()) {
                appendLine("New biome discoveries (${biomeEvents.size} total):")
                biomeEvents.groupBy { it.playerName }.entries.sortedByDescending { it.value.size }.take(3).forEach { (player, disc) ->
                    val biomes = disc.mapNotNull { it.details["biome"] }.distinct()
                    appendLine("- $player discovered ${biomes.size} new biome(s): ${biomes.take(3).joinToString(", ")}${if (biomes.size > 3) "..." else ""}")
                }
                appendLine()
            }
            if (placeEvents.isNotEmpty()) {
                appendLine("Building activity (${placeEvents.size} blocks placed, ${breakEvents.size} broken):")
                placeEvents.groupBy { it.playerName }.entries.sortedByDescending { it.value.size }.take(3).forEach { (player, places) ->
                    val fav = places.groupBy { it.details["block"] ?: "unknown" }.maxByOrNull { it.value.size }
                    appendLine("- $player placed ${places.size} blocks (favorite: ${fav?.key ?: "various"})")
                }
            }
            if (oreEvents.isNotEmpty()) {
                appendLine()
                appendLine("New ore discoveries (${oreEvents.size} total):")
                oreEvents.groupBy { it.playerName }.entries.take(3).forEach { (player, ores) ->
                    val types = ores.mapNotNull { it.details["ore"] }.distinct()
                    appendLine("- $player discovered: ${types.joinToString(", ")}")
                }
            }
        }

        val stories = generateArticles("Exploration & Building", summary, biomeEvents + placeEvents + oreEvents, maxStories = 5) ?: run {
            val s = mutableListOf<Story>()
            if (biomeEvents.isNotEmpty()) {
                val top = biomeEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("Explorer: ${top.key}", "Discovered ${top.value.size} new biome(s)", listOf(top.key), EventType.BIOME_DISCOVERY))
            }
            if (oreEvents.isNotEmpty()) {
                val top = oreEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) {
                    val types = top.value.mapNotNull { it.details["ore"] }.distinct()
                    s.add(Story("Prospector: ${top.key}", "Discovered ${types.size} ore type(s): ${types.joinToString(", ")}", listOf(top.key), EventType.ORE_DISCOVERY))
                }
            }
            if (placeEvents.isNotEmpty()) {
                val top = placeEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("Builder: ${top.key}", "Placed ${top.value.size} blocks", listOf(top.key), EventType.BLOCK_PLACE))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Exploration & Building", stories))
    }

    private fun generateEconomyAndTrading(events: List<ChronicleEvent>): List<NewspaperSection> {
        val tradeEvents = events.filter { it.type == EventType.TRADE }
        if (tradeEvents.isEmpty()) return emptyList()

        val summary = buildString {
            appendLine("Economic activity (${tradeEvents.size} transactions):")
            tradeEvents.groupBy { it.playerName }.entries.sortedByDescending { it.value.size }.take(3).forEach { (player, trades) ->
                val total = trades.mapNotNull { it.details["amount"]?.toDoubleOrNull() }.sum()
                appendLine("- $player: ${trades.size} transactions (total: ${String.format("%.2f", total)})")
            }
        }

        val stories = generateArticles("Economy & Trading", summary, tradeEvents, maxStories = 3) ?: run {
            val s = mutableListOf<Story>()
            val byPlayer = tradeEvents.groupBy { it.playerName }
            val topTrader = byPlayer.maxByOrNull { it.value.size }
            if (topTrader != null) {
                val total = topTrader.value.mapNotNull { it.details["amount"]?.toDoubleOrNull() }.sum()
                s.add(Story("Top Trader: ${topTrader.key}", "${topTrader.value.size} transactions (total: ${String.format("%.2f", total)})", listOf(topTrader.key), EventType.TRADE))
            }
            val totals = tradeEvents.mapNotNull { it.details["amount"]?.toDoubleOrNull() }
            if (totals.isNotEmpty()) {
                s.add(Story("Market Activity", "${tradeEvents.size} transactions totaling ${String.format("%.2f", totals.sum())}", emptyList(), EventType.TRADE))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Economy & Trading", stories))
    }

    private fun generateSocial(events: List<ChronicleEvent>): List<NewspaperSection> {
        val msgEvents = events.filter { it.type == EventType.MESSAGE_SENT }
        if (msgEvents.isEmpty()) return emptyList()

        val totalMessages = msgEvents.size
        val byPlayer = msgEvents.groupBy { it.playerName }
        val topChatter = byPlayer.maxByOrNull { it.value.size }

        val stories = mutableListOf<Story>()
        stories.add(Story("Messages Sent", "$totalMessages private messages were sent this cycle.", emptyList(), EventType.MESSAGE_SENT))
        if (topChatter != null) {
            stories.add(Story("Most Social: ${topChatter.key}", "${topChatter.value.size} private messages sent.", listOf(topChatter.key), EventType.MESSAGE_SENT))
        }

        return listOf(NewspaperSection("Social", stories.take(maxStories)))
    }

    private fun generateBreakingNews(events: List<ChronicleEvent>): List<NewspaperSection> {
        val endEvents = events.filter { it.type == EventType.END_ENTER }
        if (endEvents.isEmpty()) return emptyList()

        val players = endEvents.map { it.playerName }.distinct()
        val stories = listOf(
            Story(
                "Breaking: Adventurers Reach The End",
                "In a monumental achievement, ${players.size} player(s) ventured into The End dimension for the first time. The expedition was led by ${players.firstOrNull() ?: "unknown"}.",
                players,
                EventType.END_ENTER,
            )
        )

        return listOf(NewspaperSection("Breaking News", stories))
    }

    private fun generateCraftingAndEnchanting(events: List<ChronicleEvent>): List<NewspaperSection> {
        val craftEvents = events.filter { it.type == EventType.CRAFT }
        val enchantEvents = events.filter { it.type == EventType.ENCHANT }
        val furnaceEvents = events.filter { it.type == EventType.FURNACE_EXTRACT }
        if (craftEvents.isEmpty() && enchantEvents.isEmpty() && furnaceEvents.isEmpty()) return emptyList()

        val summary = buildString {
            if (craftEvents.isNotEmpty()) {
                appendLine("Items crafted (${craftEvents.size} total):")
                craftEvents.groupBy { it.details["item"] ?: "unknown" }.entries.sortedByDescending { it.value.size }.take(5).forEach { (item, evs) ->
                    appendLine("- $item: ${evs.size}x by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
                appendLine()
            }
            if (enchantEvents.isNotEmpty()) {
                appendLine("Enchantments (${enchantEvents.size} total):")
                enchantEvents.groupBy { it.details["item"] ?: "unknown" }.entries.take(5).forEach { (item, evs) ->
                    appendLine("- $item enchanted ${evs.size}x (cost: ${evs.mapNotNull { it.details["level"] }.joinToString(", ")})")
                }
                appendLine()
            }
            if (furnaceEvents.isNotEmpty()) {
                appendLine("Furnace output (${furnaceEvents.size} extracts):")
                furnaceEvents.groupBy { it.details["item"] ?: "unknown" }.entries.sortedByDescending { it.value.size }.take(3).forEach { (item, evs) ->
                    val total = evs.mapNotNull { it.details["amount"]?.toIntOrNull() }.sum()
                    appendLine("- $item: $total units by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
            }
        }

        val stories = generateArticles("Crafting & Enchanting", summary, craftEvents + enchantEvents + furnaceEvents, maxStories = 5) ?: run {
            val s = mutableListOf<Story>()
            if (craftEvents.isNotEmpty()) {
                val top = craftEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("Master Crafter: ${top.key}", "${top.value.size} items crafted", listOf(top.key), EventType.CRAFT))
            }
            if (enchantEvents.isNotEmpty()) {
                s.add(Story("Enchanting Report", "${enchantEvents.size} enchantments performed", enchantEvents.map { it.playerName }.distinct(), EventType.ENCHANT))
            }
            if (furnaceEvents.isNotEmpty()) {
                val top = furnaceEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("Industrialist: ${top.key}", "${top.value.size} furnace extracts", listOf(top.key), EventType.FURNACE_EXTRACT))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Crafting & Enchanting", stories))
    }

    private fun generateFarmingAndTaming(events: List<ChronicleEvent>): List<NewspaperSection> {
        val fishEvents = events.filter { it.type == EventType.FISH }
        val tameEvents = events.filter { it.type == EventType.TAME }
        val breedEvents = events.filter { it.type == EventType.BREED }
        val shearEvents = events.filter { it.type == EventType.SHEAR }
        val dyeEvents = events.filter { it.type == EventType.SHEEP_DYE }
        if (fishEvents.isEmpty() && tameEvents.isEmpty() && breedEvents.isEmpty() && shearEvents.isEmpty() && dyeEvents.isEmpty()) return emptyList()

        val summary = buildString {
            if (fishEvents.isNotEmpty()) {
                appendLine("Fishing (${fishEvents.size} catches):")
                fishEvents.groupBy { it.playerName }.entries.sortedByDescending { it.value.size }.take(3).forEach { (player, catches) ->
                    val types = catches.mapNotNull { it.details["caught"] }.distinct()
                    appendLine("- $player: ${catches.size} catches (${types.take(3).joinToString(", ")})")
                }
                appendLine()
            }
            if (tameEvents.isNotEmpty()) {
                appendLine("Taming (${tameEvents.size} animals tamed):")
                tameEvents.groupBy { it.details["entity"] ?: "unknown" }.entries.take(3).forEach { (entity, evs) ->
                    appendLine("- $entity: tamed by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
                appendLine()
            }
            if (breedEvents.isNotEmpty()) {
                appendLine("Breeding (${breedEvents.size} animals bred):")
                breedEvents.groupBy { it.details["entity"] ?: "unknown" }.entries.take(3).forEach { (entity, evs) ->
                    appendLine("- $entity: ${evs.size}x by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
                appendLine()
            }
            if (shearEvents.isNotEmpty()) {
                appendLine("Shearing (${shearEvents.size} animals sheared)")
            }
            if (dyeEvents.isNotEmpty()) {
                appendLine("Sheep dyed (${dyeEvents.size} times):")
                dyeEvents.groupBy { it.details["colour"] ?: "unknown" }.entries.take(3).forEach { (colour, evs) ->
                    appendLine("- $colour: ${evs.size}x")
                }
            }
        }

        val stories = generateArticles("Farming & Taming", summary, fishEvents + tameEvents + breedEvents + shearEvents + dyeEvents, maxStories = 5) ?: run {
            val s = mutableListOf<Story>()
            if (fishEvents.isNotEmpty()) {
                val top = fishEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("Angler: ${top.key}", "${top.value.size} fish caught", listOf(top.key), EventType.FISH))
            }
            if (tameEvents.isNotEmpty()) {
                s.add(Story("Animal Tamer", "${tameEvents.size} animals tamed", tameEvents.map { it.playerName }.distinct(), EventType.TAME))
            }
            if (breedEvents.isNotEmpty()) {
                s.add(Story("Breeding Report", "${breedEvents.size} animals bred", breedEvents.map { it.playerName }.distinct(), EventType.BREED))
            }
            if (shearEvents.isNotEmpty()) {
                s.add(Story("Wool Harvest", "${shearEvents.size} animals sheared", shearEvents.map { it.playerName }.distinct(), EventType.SHEAR))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Farming & Taming", stories))
    }

    private fun generateTravelAndTransport(events: List<ChronicleEvent>): List<NewspaperSection> {
        val portalEvents = events.filter { it.type == EventType.PORTAL }
        val teleportEvents = events.filter { it.type == EventType.TELEPORT }
        val vehicleEvents = events.filter { it.type == EventType.VEHICLE_RIDE }
        if (portalEvents.isEmpty() && teleportEvents.isEmpty() && vehicleEvents.isEmpty()) return emptyList()

        val summary = buildString {
            if (portalEvents.isNotEmpty()) {
                appendLine("Portal usage (${portalEvents.size} trips):")
                portalEvents.groupBy { it.details["to"] ?: "unknown" }.entries.take(3).forEach { (dest, evs) ->
                    appendLine("- To $dest: ${evs.size}x by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
                appendLine()
            }
            if (teleportEvents.isNotEmpty()) {
                appendLine("Teleports (${teleportEvents.size} total):")
                teleportEvents.groupBy { it.details["cause"] ?: "unknown" }.entries.sortedByDescending { it.value.size }.take(3).forEach { (cause, evs) ->
                    appendLine("- $cause: ${evs.size}x by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
                appendLine()
            }
            if (vehicleEvents.isNotEmpty()) {
                appendLine("Vehicles (${vehicleEvents.size} rides):")
                vehicleEvents.groupBy { it.details["vehicle"] ?: "unknown" }.entries.take(3).forEach { (vehicle, evs) ->
                    appendLine("- $vehicle: ${evs.size}x by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
            }
        }

        val stories = generateArticles("Travel & Transport", summary, portalEvents + teleportEvents + vehicleEvents, maxStories = 5) ?: run {
            val s = mutableListOf<Story>()
            if (portalEvents.isNotEmpty()) {
                s.add(Story("Portal Traffic", "${portalEvents.size} portal trips", portalEvents.map { it.playerName }.distinct(), EventType.PORTAL))
            }
            if (teleportEvents.isNotEmpty()) {
                val top = teleportEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("Frequent Teleporter: ${top.key}", "${top.value.size} teleports", listOf(top.key), EventType.TELEPORT))
            }
            if (vehicleEvents.isNotEmpty()) {
                s.add(Story("Transport Report", "${vehicleEvents.size} vehicle rides", vehicleEvents.map { it.playerName }.distinct(), EventType.VEHICLE_RIDE))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Travel & Transport", stories))
    }

    private fun generateWorldEvents(events: List<ChronicleEvent>): List<NewspaperSection> {
        val explosionEvents = events.filter { it.type == EventType.EXPLOSION }
        val lightningEvents = events.filter { it.type == EventType.LIGHTNING }
        val weatherEvents = events.filter { it.type == EventType.WEATHER }
        val thunderEvents = events.filter { it.type == EventType.THUNDER }
        val raidEvents = events.filter { it.type == EventType.RAID }
        val growEvents = events.filter { it.type == EventType.STRUCTURE_GROW }
        if (explosionEvents.isEmpty() && lightningEvents.isEmpty() && weatherEvents.isEmpty() && thunderEvents.isEmpty() && raidEvents.isEmpty() && growEvents.isEmpty()) return emptyList()

        val summary = buildString {
            if (explosionEvents.isNotEmpty()) {
                appendLine("Explosions (${explosionEvents.size} total):")
                explosionEvents.groupBy { it.details["source"] ?: "unknown" }.entries.sortedByDescending { it.value.size }.take(3).forEach { (source, evs) ->
                    val blocks = evs.mapNotNull { it.details["blocks"]?.toIntOrNull() }.sum()
                    appendLine("- $source: ${evs.size} explosions, $blocks blocks destroyed")
                }
                appendLine()
            }
            if (lightningEvents.isNotEmpty()) {
                appendLine("Lightning strikes: ${lightningEvents.size}")
                appendLine()
            }
            if (weatherEvents.isNotEmpty() || thunderEvents.isNotEmpty()) {
                appendLine("Weather changes: ${weatherEvents.size + thunderEvents.size}")
                appendLine()
            }
            if (raidEvents.isNotEmpty()) {
                appendLine("Raids triggered: ${raidEvents.size}")
                raidEvents.take(3).forEach { ev ->
                    appendLine("- by ${ev.playerName} at ${ev.details["location"]}")
                }
                appendLine()
            }
            if (growEvents.isNotEmpty()) {
                appendLine("Structures grown: ${growEvents.size}")
            }
        }

        val stories = generateArticles("World Events", summary, explosionEvents + lightningEvents + weatherEvents + thunderEvents + raidEvents + growEvents, maxStories = 5) ?: run {
            val s = mutableListOf<Story>()
            if (explosionEvents.isNotEmpty()) {
                val totalBlocks = explosionEvents.mapNotNull { it.details["blocks"]?.toIntOrNull() }.sum()
                s.add(Story("Explosions", "${explosionEvents.size} explosions destroyed $totalBlocks blocks", emptyList(), EventType.EXPLOSION))
            }
            if (lightningEvents.isNotEmpty()) {
                s.add(Story("Storm Activity", "${lightningEvents.size} lightning strikes recorded", emptyList(), EventType.LIGHTNING))
            }
            if (raidEvents.isNotEmpty()) {
                s.add(Story("Raid Alert", "${raidEvents.size} raids triggered by ${raidEvents.map { it.playerName }.distinct().joinToString(", ")}", raidEvents.map { it.playerName }.distinct(), EventType.RAID))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("World Events", stories))
    }

    private fun generateCommunityLife(events: List<ChronicleEvent>): List<NewspaperSection> {
        val chatEvents = events.filter { it.type == EventType.CHAT }
        val sleepEvents = events.filter { it.type == EventType.SLEEP }
        val consumeEvents = events.filter { it.type == EventType.ITEM_CONSUME }
        val breakEvents = events.filter { it.type == EventType.ITEM_BREAK }
        val signEvents = events.filter { it.type == EventType.SIGN_EDIT }
        val respawnEvents = events.filter { it.type == EventType.RESPAWN }
        val kickEvents = events.filter { it.type == EventType.KICK }
        val gamemodeEvents = events.filter { it.type == EventType.GAMEMODE }
        if (chatEvents.isEmpty() && sleepEvents.isEmpty() && consumeEvents.isEmpty() && breakEvents.isEmpty() && signEvents.isEmpty() && respawnEvents.isEmpty() && kickEvents.isEmpty() && gamemodeEvents.isEmpty()) return emptyList()

        val summary = buildString {
            if (chatEvents.isNotEmpty()) {
                appendLine("Chat activity (${chatEvents.size} messages):")
                chatEvents.groupBy { it.playerName }.entries.sortedByDescending { it.value.size }.take(3).forEach { (player, msgs) ->
                    appendLine("- $player: ${msgs.size} messages")
                }
                appendLine()
            }
            if (sleepEvents.isNotEmpty()) {
                appendLine("Sleep (${sleepEvents.size} times):")
                sleepEvents.groupBy { it.playerName }.entries.take(3).forEach { (player, evs) ->
                    appendLine("- $player slept ${evs.size}x")
                }
                appendLine()
            }
            if (consumeEvents.isNotEmpty()) {
                appendLine("Consumption (${consumeEvents.size} items):")
                consumeEvents.groupBy { it.details["item"] ?: "unknown" }.entries.sortedByDescending { it.value.size }.take(3).forEach { (item, evs) ->
                    appendLine("- $item: ${evs.size}x")
                }
                appendLine()
            }
            if (breakEvents.isNotEmpty()) {
                appendLine("Tools broken (${breakEvents.size}):")
                breakEvents.groupBy { it.details["item"] ?: "unknown" }.entries.take(3).forEach { (item, evs) ->
                    appendLine("- $item: ${evs.size}x by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
                appendLine()
            }
            if (signEvents.isNotEmpty()) {
                appendLine("Signs edited: ${signEvents.size}")
                signEvents.take(3).forEach { ev ->
                    appendLine("- ${ev.playerName}: \"${ev.details["text"]?.take(50)}\"")
                }
                appendLine()
            }
            if (respawnEvents.isNotEmpty()) {
                appendLine("Respawns: ${respawnEvents.size}")
                appendLine()
            }
            if (kickEvents.isNotEmpty()) {
                appendLine("Kicks: ${kickEvents.size}")
                kickEvents.take(3).forEach { ev ->
                    appendLine("- ${ev.playerName}: ${ev.details["reason"]}")
                }
                appendLine()
            }
            if (gamemodeEvents.isNotEmpty()) {
                appendLine("Game mode changes: ${gamemodeEvents.size}")
                gamemodeEvents.take(3).forEach { ev ->
                    appendLine("- ${ev.playerName}: ${ev.details["from"]} → ${ev.details["to"]}")
                }
            }
        }

        val stories = generateArticles("Community Life", summary, chatEvents + sleepEvents + consumeEvents + breakEvents + signEvents + respawnEvents + kickEvents + gamemodeEvents, maxStories = 5) ?: run {
            val s = mutableListOf<Story>()
            if (chatEvents.isNotEmpty()) {
                val top = chatEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("Chattiest: ${top.key}", "${top.value.size} messages sent", listOf(top.key), EventType.CHAT))
            }
            if (sleepEvents.isNotEmpty()) {
                s.add(Story("Restful Nights", "${sleepEvents.size} bed entries recorded", sleepEvents.map { it.playerName }.distinct(), EventType.SLEEP))
            }
            if (consumeEvents.isNotEmpty()) {
                s.add(Story("Dining Report", "${consumeEvents.size} items consumed", consumeEvents.map { it.playerName }.distinct(), EventType.ITEM_CONSUME))
            }
            if (breakEvents.isNotEmpty()) {
                s.add(Story("Tool Casualties", "${breakEvents.size} tools broken", breakEvents.map { it.playerName }.distinct(), EventType.ITEM_BREAK))
            }
            if (signEvents.isNotEmpty()) {
                s.add(Story("Sign Posts", "${signEvents.size} signs written", signEvents.map { it.playerName }.distinct(), EventType.SIGN_EDIT))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Community Life", stories))
    }

    private fun generateAdventures(events: List<ChronicleEvent>): List<NewspaperSection> {
        val projectileEvents = events.filter { it.type == EventType.PROJECTILE_LAUNCH }
        val potionEvents = events.filter { it.type == EventType.POTION_THROW }
        val fireworkEvents = events.filter { it.type == EventType.FIREWORK }
        val riptideEvents = events.filter { it.type == EventType.RIPTIDE }
        val flightEvents = events.filter { it.type == EventType.FLIGHT_TOGGLE }
        val glideEvents = events.filter { it.type == EventType.GLIDE_TOGGLE }
        val eggEvents = events.filter { it.type == EventType.EGG_THROW }
        val bucketEvents = events.filter { it.type == EventType.BUCKET }
        val hangingBreakEvents = events.filter { it.type == EventType.HANGING_BREAK }
        val hangingPlaceEvents = events.filter { it.type == EventType.HANGING_PLACE }
        val transformEvents = events.filter { it.type == EventType.ENTITY_TRANSFORM }
        val slimeEvents = events.filter { it.type == EventType.SLIME_SPLIT }
        val pigZapEvents = events.filter { it.type == EventType.PIG_ZAP }
        val creeperEvents = events.filter { it.type == EventType.CREEPER_POWER }
        val allAdventure = projectileEvents + potionEvents + fireworkEvents + riptideEvents + flightEvents + glideEvents + eggEvents + bucketEvents + hangingBreakEvents + hangingPlaceEvents + transformEvents + slimeEvents + pigZapEvents + creeperEvents
        if (allAdventure.isEmpty()) return emptyList()

        val summary = buildString {
            if (projectileEvents.isNotEmpty()) {
                appendLine("Projectiles launched (${projectileEvents.size}):")
                projectileEvents.groupBy { it.details["projectile"] ?: "unknown" }.entries.sortedByDescending { it.value.size }.take(3).forEach { (proj, evs) ->
                    appendLine("- $proj: ${evs.size}x by ${evs.map { it.playerName }.distinct().joinToString(", ")}")
                }
                appendLine()
            }
            if (potionEvents.isNotEmpty()) {
                appendLine("Potions thrown: ${potionEvents.size}")
                appendLine()
            }
            if (fireworkEvents.isNotEmpty()) {
                appendLine("Fireworks: ${fireworkEvents.size}")
                appendLine()
            }
            if (riptideEvents.isNotEmpty()) {
                appendLine("Riptide uses: ${riptideEvents.size}")
                appendLine()
            }
            if (flightEvents.isNotEmpty()) {
                appendLine("Flight toggles: ${flightEvents.size}")
                appendLine()
            }
            if (glideEvents.isNotEmpty()) {
                appendLine("Elytra glides: ${glideEvents.size}")
                appendLine()
            }
            if (eggEvents.isNotEmpty()) {
                appendLine("Eggs thrown: ${eggEvents.size}")
                appendLine()
            }
            if (bucketEvents.isNotEmpty()) {
                appendLine("Bucket usage (${bucketEvents.size}):")
                bucketEvents.groupBy { it.details["action"] ?: "unknown" }.entries.take(2).forEach { (action, evs) ->
                    appendLine("- $action: ${evs.size}x")
                }
                appendLine()
            }
            if (hangingBreakEvents.isNotEmpty() || hangingPlaceEvents.isNotEmpty()) {
                appendLine("Hanging entities: ${hangingPlaceEvents.size} placed, ${hangingBreakEvents.size} broken")
                appendLine()
            }
            if (transformEvents.isNotEmpty()) {
                appendLine("Entity transforms (${transformEvents.size}):")
                transformEvents.groupBy { Pair(it.details["from"] ?: "unknown", it.details["to"] ?: "unknown") }.entries.take(3).forEach { (key, evs) ->
                    val (from, to) = key
                    appendLine("- $from → $to: ${evs.size}x")
                }
                appendLine()
            }
            if (slimeEvents.isNotEmpty()) {
                appendLine("Slime splits: ${slimeEvents.size}")
                appendLine()
            }
            if (pigZapEvents.isNotEmpty()) {
                appendLine("Pigs zapped by lightning: ${pigZapEvents.size}")
                appendLine()
            }
            if (creeperEvents.isNotEmpty()) {
                appendLine("Creepers charged: ${creeperEvents.size}")
            }
        }

        val stories = generateArticles("Adventures", summary, allAdventure, maxStories = 5) ?: run {
            val s = mutableListOf<Story>()
            if (projectileEvents.isNotEmpty()) {
                val top = projectileEvents.groupBy { it.playerName }.maxByOrNull { it.value.size }
                if (top != null) s.add(Story("Marksman: ${top.key}", "${top.value.size} projectiles launched", listOf(top.key), EventType.PROJECTILE_LAUNCH))
            }
            if (fireworkEvents.isNotEmpty()) {
                s.add(Story("Fireworks Display", "${fireworkEvents.size} fireworks launched", fireworkEvents.map { it.playerName }.distinct(), EventType.FIREWORK))
            }
            if (glideEvents.isNotEmpty()) {
                s.add(Story("Elytra Flights", "${glideEvents.size} glide toggles", glideEvents.map { it.playerName }.distinct(), EventType.GLIDE_TOGGLE))
            }
            if (transformEvents.isNotEmpty()) {
                s.add(Story("Entity Transforms", "${transformEvents.size} entities transformed", emptyList(), EventType.ENTITY_TRANSFORM))
            }
            if (bucketEvents.isNotEmpty()) {
                s.add(Story("Bucket Activity", "${bucketEvents.size} bucket operations", bucketEvents.map { it.playerName }.distinct(), EventType.BUCKET))
            }
            s.take(maxStories)
        }

        return listOf(NewspaperSection("Adventures", stories))
    }

    private fun generateStatistics(events: List<ChronicleEvent>): NewspaperSection {
        val stats = mutableListOf<Story>()
        // System-generated events (weather, explosions, entity transforms, etc.) are recorded
        // with a blank playerUuid and a sentinel playerName (e.g. "world", "nature", "unknown").
        // Only count events with a real player attached as "active players".
        val allPlayers = events.filter { it.playerUuid.isNotBlank() }.map { it.playerName }.distinct()
        stats.add(Story("Active Players", "${allPlayers.size} unique player(s) this cycle", allPlayers, null))
        stats.add(Story("Total Events", "${events.size} events recorded", emptyList(), null))
        val joinCount = events.count { it.type == EventType.PLAYER_JOIN }
        if (joinCount > 0) stats.add(Story("Logins", "$joinCount total logins", emptyList(), null))
        EventType.entries.forEach { type ->
            val count = events.count { it.type == type }
            if (count > 0) {
                stats.add(Story(type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, "$count events", emptyList(), type))
            }
        }
        return NewspaperSection("Statistics", stats)
    }

    private fun generateArticles(
        sectionTitle: String,
        summary: String,
        events: List<ChronicleEvent>,
        maxStories: Int,
    ): List<Story>? {
        if (!llmEnabled || llmProvider == null || summary.isBlank()) return null

        val systemPrompt = llmSystemPrompt
            .replace("{tone}", newspaperConfig.tone)
            .replace("{maxArticleCharacters}", newspaperConfig.maxArticleCharacters.toString())

        return try {
            logger.fine("Requesting LLM article for section '$sectionTitle' from ${events.size} events.")
            val result = llmProvider.generate(systemPrompt, sectionTitle, summary)
            if (result != null) {
                val players = events.map { it.playerName }.distinct()
                val types = events.map { it.type }.distinct()
                listOf(Story(result.headline, result.body, players, types.firstOrNull()))
            } else {
                logger.warning("LLM returned no usable article for '$sectionTitle'; using template copy.")
                null
            }
        } catch (e: Exception) {
            logger.warning("LLM article generation failed for '$sectionTitle': ${e.message}; using template copy.")
            null
        }
    }

    private fun structureStory(story: Story): Story {
        val headline = story.headline.trim().replace(Regex("[\\r\\n]+"), " ").take(60).ifBlank { "News Brief" }
        val body = story.body.trim().replace(Regex("\\s+"), " ").ifBlank { "No further details were available at press time." }
        val completeBody = if (body.last() in ".!?") body else "$body."
        return story.copy(headline = headline, body = completeBody, byline = newspaperConfig.byline)
    }

    private fun redactEvent(event: ChronicleEvent): ChronicleEvent {
        val details = event.details.filterKeys { key ->
            val normalized = key.lowercase()
            (privacyConfig.includeChatExcerpts || normalized !in setOf("text", "message", "chat")) &&
                (privacyConfig.includeCoordinates || normalized !in setOf("x", "y", "z", "coordinates", "location"))
        }
        return event.copy(details = details)
    }

    private fun importanceScore(story: Story): Int = when (story.eventType) {
        EventType.END_ENTER, EventType.FIRST_JOIN, EventType.FIRST_DEATH, EventType.RAID -> 100
        EventType.ADVANCEMENT, EventType.MILESTONE, EventType.MILESTONE_PLAYTIME, EventType.MILESTONE_LOGIN_STREAK -> 80
        EventType.PVP_KILL, EventType.DEATH, EventType.EXPLOSION, EventType.LIGHTNING -> 60
        EventType.BIOME_DISCOVERY, EventType.ORE_DISCOVERY, EventType.TRADE -> 40
        else -> 20
    }
}
