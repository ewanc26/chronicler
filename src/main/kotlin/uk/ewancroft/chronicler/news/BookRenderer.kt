package uk.ewancroft.chronicler.news

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import uk.ewancroft.chronicler.config.NewspaperConfig
import java.util.Date

/**
 * Lays an issue out as a written book. Book pages clip rather than scroll, so
 * text is wrapped with the client's own glyph widths and flowed across pages
 * that never exceed [LINES_PER_PAGE] lines of [PAGE_WIDTH] pixels.
 */
class BookRenderer(
    private val newspaperConfig: NewspaperConfig,
) {

    companion object {
        /** Vanilla BookViewScreen: 114px text width, 128px text height at 9px per line. */
        const val PAGE_WIDTH = 114
        const val LINES_PER_PAGE = 14
        const val MAX_PAGES = 100

        /** Leaves a pixel of slack so rounding differences never trigger a client-side rewrap. */
        private const val WRAP_WIDTH = PAGE_WIDTH - 1

        /** Identifies which issue a newspaper item holds. */
        val ISSUE_KEY = NamespacedKey("chronicler", "issue_number")

        /**
         * The issue a newspaper item holds. Papers printed before items were
         * tagged are recognised by the "<title> #<n>" title and author they
         * were always given, so old copies in chests open in the reader too.
         */
        fun issueNumberOf(item: ItemStack?, config: NewspaperConfig? = null): Int? {
            val meta = item?.takeIf { it.type == Material.WRITTEN_BOOK }?.itemMeta as? BookMeta ?: return null
            meta.persistentDataContainer.get(ISSUE_KEY, PersistentDataType.INTEGER)?.let { return it }
            if (config == null || meta.author != config.author) return null
            val title = meta.title ?: return null
            val prefix = "${config.title} #".take(31)
            return if (title.startsWith(prefix)) title.removePrefix(prefix).toIntOrNull() else null
        }

        /** A section's first story needs its header, headline, byline and a couple of body lines. */
        private const val MIN_LINES_FOR_STORY_START = 5
    }

    /** One laid-out line; [plain] is kept for measuring and tests. */
    internal data class Line(val component: Component, val plain: String, val bold: Boolean = false)

    private val accent = TextColor.color(newspaperConfig.accentColor)
    private val primaryText = TextColor.color(newspaperConfig.primaryTextColor)
    private val secondaryText = TextColor.color(newspaperConfig.secondaryTextColor)
    private val mutedText = TextColor.color(newspaperConfig.mutedTextColor)

    fun renderToBook(newspaper: Newspaper): ItemStack {
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as BookMeta

        meta.setTitle("${newspaperConfig.title} #${newspaper.issueNumber}".take(32))
        meta.setAuthor(newspaperConfig.author)
        meta.setGeneration(BookMeta.Generation.ORIGINAL)
        meta.addPages(*layout(newspaper).map(::pageComponent).toTypedArray())
        meta.persistentDataContainer.set(ISSUE_KEY, PersistentDataType.INTEGER, newspaper.issueNumber)
        val lead = newspaper.sections.firstOrNull { it.stories.isNotEmpty() }?.stories?.first()
        meta.lore(listOfNotNull(
            Component.text("No. ${newspaper.issueNumber} · ${dateLine(newspaper.toTime)}", mutedText).decoration(TextDecoration.ITALIC, false),
            lead?.let { Component.text("“${it.headline}”", secondaryText) },
            Component.text("Right-click to read", accent).decoration(TextDecoration.ITALIC, false),
        ))

        book.itemMeta = meta
        // The resource pack swaps in the newspaper model for books carrying this tag;
        // without the pack the item simply looks and reads like a written book.
        try {
            book.setData(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addString(NewspaperPack.ITEM_MODEL_STRING).build(),
            )
            // Written books shimmer like enchanted items; newsprint should not.
            book.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
        } catch (_: UnsupportedOperationException) {
            // Test servers without data component support.
        }
        return book
    }

    internal fun layout(newspaper: Newspaper): List<List<Line>> {
        // Sections are laid out first so the cover can list their page numbers.
        val body = mutableListOf<List<Line>>()
        val sectionStarts = mutableListOf<Pair<String, Int>>()
        for (section in newspaper.sections.filter { it.stories.isNotEmpty() }) {
            val pages = layoutSection(section)
            sectionStarts += section.title to body.size
            body += pages
        }

        val cover = layoutCover(newspaper, sectionStarts)
        val contentsOffset = cover.size + 1 // page numbers are 1-based
        val coverWithLinks = cover.map { page ->
            page.map { line -> line.withPageOffset(contentsOffset) }
        }

        val pages = coverWithLinks + body + listOf(footerPage(newspaper))
        if (pages.size <= MAX_PAGES) return pages
        return pages.take(MAX_PAGES - 1) + listOf(listOf(
            text("— Issue truncated —", mutedText, italic = true),
            text("This issue is longer than a book allows. Read it in full on the web edition.", mutedText),
        ).flatten())
    }

    private fun layoutCover(newspaper: Newspaper, sectionStarts: List<Pair<String, Int>>): List<List<Line>> {
        val lines = mutableListOf<Line>()
        lines += text(newspaperConfig.title, accent, bold = true)
        lines += rule()
        lines += text("No. ${newspaper.issueNumber} · ${dateLine(newspaper.toTime)}", secondaryText)
        lines += blank()
        lines += text(newspaperConfig.titlePageText, mutedText, italic = true)
        if (sectionStarts.isNotEmpty()) {
            lines += blank()
            lines += text("IN THIS ISSUE", accent, bold = true)
            sectionStarts.forEach { (title, index) -> lines += contentsEntry(title, index) }
        }
        return lines.chunked(LINES_PER_PAGE)
    }

    private fun layoutSection(section: NewspaperSection): List<List<Line>> {
        val pages = mutableListOf<List<Line>>()
        var page = mutableListOf<Line>()
        val header = text(section.title.uppercase(), accent, bold = true) + rule()

        fun newPage(continued: Boolean) {
            if (page.isNotEmpty()) pages += page
            page = mutableListOf()
            if (continued) page += text("${section.title.uppercase()} (cont.)", mutedText, italic = true)
        }

        page += header
        section.stories.forEachIndexed { index, story ->
            val storyLines = mutableListOf<Line>()
            if (index > 0) storyLines += blank()
            storyLines += text(story.headline, primaryText, bold = true)
            storyLines += text("By ${story.byline}", mutedText, italic = true)
            storyLines += text(story.body, secondaryText)
            if (story.players.isNotEmpty()) storyLines += text("Filed under: ${story.players.joinToString(", ")}", mutedText)

            // Avoid stranding a headline at the foot of a page.
            val remaining = LINES_PER_PAGE - page.size
            if (remaining < minOf(MIN_LINES_FOR_STORY_START, storyLines.size)) newPage(continued = true)

            for (line in storyLines) {
                if (page.size == LINES_PER_PAGE) newPage(continued = true)
                // Never open a continuation page with a spacer line.
                if (line.plain.isEmpty() && page.size <= 1 && pages.isNotEmpty()) continue
                page += line
            }
        }
        if (page.isNotEmpty()) pages += page
        return pages
    }

    private fun footerPage(newspaper: Newspaper): List<Line> = buildList {
        addAll(text("— End of Issue —", mutedText))
        addAll(blank())
        addAll(text("${newspaperConfig.title}, No. ${newspaper.issueNumber}", secondaryText))
        addAll(text("Published ${dateLine(newspaper.toTime)}", mutedText))
    }

    private fun contentsEntry(title: String, bodyIndex: Int): Line {
        val label = MinecraftFont.wrap(title, WRAP_WIDTH - 24).first()
        // Page numbers are resolved once the cover length is known; see withPageOffset.
        return Line(
            Component.text("• $label", secondaryText)
                .insertion("$bodyIndex"),
            "• $label",
        )
    }

    private fun Line.withPageOffset(offset: Int): Line {
        val index = component.insertion()?.toIntOrNull() ?: return this
        val pageNumber = index + offset
        val fixed = MinecraftFont.width("$plain  $pageNumber")
        val leader = ".".repeat(((WRAP_WIDTH - fixed) / MinecraftFont.advance('.')).coerceAtLeast(0))
        return Line(
            Component.text()
                .append(Component.text(plain, secondaryText))
                .append(Component.text(" $leader ", mutedText))
                .append(Component.text("$pageNumber", accent))
                .clickEvent(ClickEvent.changePage(pageNumber))
                .hoverEvent(HoverEvent.showText(Component.text("Go to page $pageNumber")))
                .build(),
            "$plain $leader $pageNumber",
        )
    }

    private fun text(content: String, color: TextColor, bold: Boolean = false, italic: Boolean = false): List<Line> =
        MinecraftFont.wrap(content, WRAP_WIDTH, bold).map { line ->
            var component = Component.text(line, color)
            if (bold) component = component.decorate(TextDecoration.BOLD)
            if (italic) component = component.decorate(TextDecoration.ITALIC)
            Line(component, line, bold)
        }

    private fun rule(): List<Line> {
        val count = WRAP_WIDTH / MinecraftFont.advance('=')
        return listOf(Line(Component.text("=".repeat(count), mutedText), "=".repeat(count)))
    }

    private fun blank(): List<Line> = listOf(Line(Component.empty(), ""))

    private fun pageComponent(lines: List<Line>): Component =
        Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines.map { it.component })

    private fun dateLine(time: Long): String =
        newspaperConfig.formatDate(time, java.time.format.FormatStyle.MEDIUM)
}
