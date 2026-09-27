package uk.ewancroft.chronicler.integration

import org.bukkit.entity.Player
import org.geysermc.cumulus.form.SimpleForm
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.news.Newspaper

/** A Bedrock form's text: kept free of Cumulus types so it can be tested without Floodgate. */
data class BedrockPage(val title: String, val content: String, val buttons: List<String>)

/** Builds the text edition as Bedrock form pages (Bedrock uses § formatting codes). */
object BedrockPages {

    fun front(config: NewspaperConfig, issue: Newspaper, date: String): BedrockPage {
        val sections = issue.sections.filter { it.stories.isNotEmpty() }
        val lead = sections.firstOrNull()?.stories?.firstOrNull()
        val content = buildString {
            append("§7No. ${issue.issueNumber} · $date§r\n\n")
            if (lead != null) append("§l${lead.headline}§r\n${lead.body}\n")
        }
        val buttons = sections.map { section ->
            "${section.title}\n§8${section.stories.size} stor${if (section.stories.size == 1) "y" else "ies"}"
        }
        return BedrockPage("${config.title} — No. ${issue.issueNumber}", content, buttons)
    }

    fun section(issue: Newspaper, index: Int): BedrockPage {
        val sections = issue.sections.filter { it.stories.isNotEmpty() }
        val section = sections[index]
        val content = section.stories.joinToString("\n\n") { story ->
            "§l${story.headline}§r\n${story.body}\n§o§7By ${story.byline}§r"
        }
        val buttons = buildList {
            add("« Front page")
            if (index < sections.lastIndex) add("${sections[index + 1].title} »")
        }
        return BedrockPage("${section.title} — No. ${issue.issueNumber}", content, buttons)
    }
}

/**
 * The text edition as Bedrock forms, for players joining through Geyser.
 * Bedrock cannot use Java resource packs or dialogs, so this is their reader.
 * Only instantiated when Floodgate is present.
 */
class BedrockReader(
    private val floodgate: FloodgateHook,
    private val config: NewspaperConfig,
    private val dateLine: (Player, Long) -> String,
) {

    fun open(player: Player, issue: Newspaper) {
        val page = BedrockPages.front(config, issue, dateLine(player, issue.toTime))
        val form = SimpleForm.builder().title(page.title).content(page.content)
        page.buttons.forEachIndexed { i, label -> form.button(label) { openSection(player, issue, i) } }
        floodgate.send(player.uniqueId, form)
    }

    private fun openSection(player: Player, issue: Newspaper, index: Int) {
        val page = BedrockPages.section(issue, index)
        val form = SimpleForm.builder().title(page.title).content(page.content)
            .button(page.buttons[0]) { open(player, issue) }
        if (page.buttons.size > 1) form.button(page.buttons[1]) { openSection(player, issue, index + 1) }
        floodgate.send(player.uniqueId, form)
    }
}
