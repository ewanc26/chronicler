package uk.ewancroft.chronicler.reader

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.news.MinecraftFont
import uk.ewancroft.chronicler.news.Newspaper
import uk.ewancroft.chronicler.news.NewspaperPack
import org.bukkit.inventory.ItemStack
import uk.ewancroft.chronicler.integration.BedrockReader
import uk.ewancroft.chronicler.integration.ClientKind
import uk.ewancroft.chronicler.integration.ClientSupport
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The in-game newspaper reader. Players whose client has the Chronicler pack
 * see the typeset broadsheet pages; everyone else (and anyone who prefers
 * larger type) gets a text edition laid out as dialog screens.
 */
class NewspaperReader(
    private val config: NewspaperConfig,
    private val packs: ResourcePackService,
    private val clients: ClientSupport,
    private val bookFor: (Newspaper) -> ItemStack,
    private val findIssue: (Int?) -> Newspaper?,
) {
    private val bedrock: BedrockReader? = clients.floodgate?.let { BedrockReader(it, config, ::date) }

    // Dialogs sit on a dark backdrop, so the newsprint accent is lifted towards white to stay legible.
    private val accent = TextColor.lerp(0.55f, TextColor.color(config.accentColor), NamedTextColor.WHITE)
    private val muted = NamedTextColor.GRAY

    /** Opens [issueNumber] (or the latest issue) at its front page. Returns false if there is none. */
    fun open(player: Player, issueNumber: Int? = null): Boolean {
        val issue = findIssue(issueNumber) ?: return false
        when (clients.kind(player)) {
            ClientKind.BEDROCK -> bedrock?.open(player, issue) ?: player.openBook(bookFor(issue))
            // Older clients see dialogs as chest menus (ViaBackwards); the book is laid out for them.
            ClientKind.LEGACY_JAVA -> player.openBook(bookFor(issue))
            ClientKind.MODERN -> {
                val pages = packs.pagesFor(player, issue.issueNumber)
                if (pages != null) showPrinted(player, issue, pages, 0) else showFront(player, issue)
            }
        }
        return true
    }

    // ---- Printed edition ---------------------------------------------------

    private fun showPrinted(player: Player, issue: Newspaper, pages: List<NewspaperPack.PageGlyphs>, index: Int) {
        val page = pages[index]
        val image = Component.text(page.text)
            .font(Key.key(page.font))
            .color(NamedTextColor.WHITE)
            .shadowColor(ShadowColor.none())
        // Each tile row occupies one 9px text line but draws taller; pad with blank
        // lines so the dialog reserves the page's full height.
        val totalLines = (page.guiHeight + 8) / 9
        val reserve = Component.text("\n".repeat((totalLines - page.lines).coerceAtLeast(0) + 1))
        // Headroom so line measurement (which may ignore the 1px backspaces) never wraps a row.
        val body = DialogBody.plainMessage(Component.text().append(image).append(reserve).build(), wrapWidth(page))

        val buttons = buildList {
            add(button(if (index > 0) "◀ Page $index" else "◀", index > 0) { showPrinted(it, issue, pages, index - 1) })
            add(button("Text edition", true, "Read this issue in large type") { showFront(it, issue) })
            add(button(if (index < pages.lastIndex) "Page ${index + 2} ▶" else "▶", index < pages.lastIndex) {
                showPrinted(it, issue, pages, index + 1)
            })
        }
        player.showDialog(dialog(title(issue, "Page ${index + 1} of ${pages.size}"), listOf(body), buttons, columns = 3))
    }

    // ---- Text edition ------------------------------------------------------

    private fun showFront(player: Player, issue: Newspaper) {
        val sections = issue.sections.filter { it.stories.isNotEmpty() }
        val bodies = mutableListOf<DialogBody>()
        bodies += DialogBody.plainMessage(
            Component.text()
                .append(Component.text(config.title, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("No. ${issue.issueNumber} · ${date(player, issue.toTime)}", muted))
                .build(),
            320,
        )
        sections.firstOrNull()?.stories?.firstOrNull()?.let { lead ->
            bodies += DialogBody.plainMessage(Component.text(lead.headline, accent, TextDecoration.BOLD), 320)
            bodies += DialogBody.plainMessage(Component.text(lead.body, NamedTextColor.GRAY), 320)
        }
        val buttons = sections.mapIndexed { i, section ->
            button(section.title, true, "${section.stories.size} stor${if (section.stories.size == 1) "y" else "ies"}") {
                showSection(it, issue, i)
            }
        }.toMutableList()
        packs.pagesFor(player, issue.issueNumber)?.let { pages ->
            buttons.add(0, button("Printed edition", true) { showPrinted(it, issue, pages, 0) })
        }
        player.showDialog(dialog(title(issue, "Front page"), bodies, buttons, columns = 2))
    }

    private fun showSection(player: Player, issue: Newspaper, index: Int) {
        val sections = issue.sections.filter { it.stories.isNotEmpty() }
        val section = sections[index]
        val bodies = mutableListOf<DialogBody>()
        section.stories.forEach { story ->
            bodies += DialogBody.plainMessage(Component.text(story.headline, accent, TextDecoration.BOLD), 320)
            bodies += DialogBody.plainMessage(
                Component.text()
                    .append(Component.text(story.body, NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text("By ${story.byline}", muted, TextDecoration.ITALIC))
                    .build(),
                320,
            )
        }
        val buttons = listOf(
            button(if (index > 0) "◀ ${sections[index - 1].title}" else "◀", index > 0) { showSection(it, issue, index - 1) },
            button("Front page", true) { showFront(it, issue) },
            button(if (index < sections.lastIndex) "${sections[index + 1].title} ▶" else "▶", index < sections.lastIndex) {
                showSection(it, issue, index + 1)
            },
        )
        player.showDialog(dialog(title(issue, section.title), bodies, buttons, columns = 3))
    }

    // ---- Dialog plumbing ---------------------------------------------------

    private fun dialog(title: Component, bodies: List<DialogBody>, buttons: List<ActionButton>, columns: Int): Dialog =
        Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(bodies)
                        .build()
                )
                .type(
                    DialogType.multiAction(buttons)
                        .columns(columns)
                        .exitAction(closeButton())
                        .build()
                )
        }

    private fun button(label: String, enabled: Boolean, tooltip: String? = null, onClick: (Player) -> Unit): ActionButton {
        // Wide enough for "Crafting & Enchanting" and similar section names (dialog buttons max out at 1024).
        val width = (MinecraftFont.width(label) + 16).coerceIn(110, 200)
        val builder = ActionButton.builder(Component.text(label, if (enabled) NamedTextColor.WHITE else NamedTextColor.DARK_GRAY))
            .width(width)
        tooltip?.let { builder.tooltip(Component.text(it)) }
        if (enabled) {
            builder.action(DialogAction.customClick({ _, audience -> (audience as? Player)?.let(onClick) }, CALLBACK_OPTIONS))
        }
        return builder.build()
    }

    private fun wrapWidth(page: NewspaperPack.PageGlyphs) = page.guiWidth + page.guiWidth / 16 + 8

    /**
     * afterAction NONE keeps the screen up while the next page is on its way,
     * but it also applies to the exit button, which would then do nothing
     * without an action of its own; so Close closes the dialog explicitly.
     */
    private fun closeButton(): ActionButton =
        ActionButton.builder(Component.text("Close"))
            .width(100)
            .action(DialogAction.customClick({ _, audience -> audience.closeDialog() }, CALLBACK_OPTIONS))
            .build()

    private fun title(issue: Newspaper, where: String): Component =
        Component.text("${config.title} — No. ${issue.issueNumber} — $where")

    /** Dates in the reader follow the player's client language. */
    private fun date(player: Player, time: Long): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
            .withLocale(player.locale())
            .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

    private companion object {
        val CALLBACK_OPTIONS: ClickCallback.Options = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofHours(1))
            .build()
    }
}
