package uk.ewancroft.chronicler.contrib

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.input.TextDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration

/** Dialog screens for writing to the paper, voting in the poll, and moderating submissions. */
class ContributionScreens(
    private val store: Contributions,
    private val limits: ContributionLimits,
    private val paperTitle: String,
) {

    fun openWrite(player: Player) {
        val inputs = listOf(
            DialogInput.singleOption("kind", Component.text("What are you sending?"), listOf(
                SingleOptionDialogInput.OptionEntry.create("letter", Component.text(SubmissionKind.LETTER.label), true),
                SingleOptionDialogInput.OptionEntry.create("classified", Component.text(SubmissionKind.CLASSIFIED.label), false),
            )).width(300).build(),
            DialogInput.text("headline", Component.text("Headline")).width(300).maxLength(limits.headlineLength).build(),
            DialogInput.text("body", Component.text("Your words"))
                .width(300)
                .maxLength(limits.letterLength)
                .multiline(TextDialogInput.MultilineOptions.create(10, 110))
                .build(),
        )
        val intro = DialogBody.plainMessage(Component.text(
            "Letters are printed in full under your name. Classified adverts are short (up to ${limits.classifiedLength} characters)." +
                if (limits.requireApproval) " The editor reviews everything before it is printed." else "",
            NamedTextColor.GRAY,
        ), 300)
        player.showDialog(dialog("Write to $paperTitle", listOf(intro), inputs, listOf(
            action("Send to the editor") { p, view ->
                val kind = if (view.getText("kind") == "classified") SubmissionKind.CLASSIFIED else SubmissionKind.LETTER
                submit(p, kind, view.getText("headline").orEmpty(), view.getText("body").orEmpty())
            },
        )))
    }

    /** Shared by dialogs, forms and commands so every route applies the same rules and notices. */
    fun submit(player: Player, kind: SubmissionKind, headline: String, body: String) {
        when (val result = store.submit(kind, player.name, player.uniqueId.toString(), headline, body)) {
            is Contributions.Result.Rejected -> player.sendMessage(Component.text(result.reason, NamedTextColor.RED))
            is Contributions.Result.Accepted -> {
                val s = result.submission
                if (s.status == SubmissionStatus.PENDING) {
                    player.sendMessage(Component.text("Thanks! Your ${kind.label.lowercase()} is with the editor.", NamedTextColor.GREEN))
                    notifyEditors(Component.text("New ${kind.label.lowercase()} from ${player.name}: \"${s.headline}\". Review with /chronicler submissions.", NamedTextColor.GOLD))
                } else {
                    player.sendMessage(Component.text("Thanks! Your ${kind.label.lowercase()} will appear in the next issue.", NamedTextColor.GREEN))
                }
            }
        }
    }

    fun openPoll(player: Player) {
        val poll = store.poll()
        if (poll == null) {
            player.sendMessage(Component.text("There is no reader poll running right now.", NamedTextColor.GRAY))
            return
        }
        val current = poll.votes[player.uniqueId.toString()]
        val choice = DialogInput.singleOption("choice", Component.text("Your answer"), poll.options.mapIndexed { i, label ->
            SingleOptionDialogInput.OptionEntry.create("$i", Component.text(label), i == (current ?: 0))
        }).width(300).build()
        val body = DialogBody.plainMessage(Component.text()
            .append(Component.text(poll.question, NamedTextColor.WHITE, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text("Results are printed in the next issue. You can change your vote until then.", NamedTextColor.GRAY))
            .build(), 300)
        player.showDialog(dialog("Reader Poll", listOf(body), listOf(choice), listOf(
            action(if (current == null) "Vote" else "Change vote") { p, view ->
                val index = view.getText("choice")?.toIntOrNull()
                if (index != null && store.vote(p.uniqueId.toString(), index)) {
                    p.sendMessage(Component.text("Vote recorded: ${poll.options[index]}.", NamedTextColor.GREEN))
                }
            },
        )))
    }

    fun openCreatePoll(player: Player) {
        val inputs = listOf(
            DialogInput.text("question", Component.text("Question")).width(300).maxLength(120).build(),
            DialogInput.text("options", Component.text("Options, one per line (2-6)"))
                .width(300).maxLength(300)
                .multiline(TextDialogInput.MultilineOptions.create(6, 80))
                .build(),
        )
        player.showDialog(dialog("New reader poll", emptyList(), inputs, listOf(
            action("Start poll") { p, view ->
                val error = store.createPoll(view.getText("question").orEmpty(), view.getText("options").orEmpty().split('\n'))
                p.sendMessage(Component.text(error ?: "Poll started. Players can vote with /chronicler poll.", if (error == null) NamedTextColor.GREEN else NamedTextColor.RED))
            },
        )))
    }

    fun openModeration(player: Player) {
        val pending = store.pending().take(6)
        if (pending.isEmpty()) {
            player.sendMessage(Component.text("Nothing is waiting for the editor.", NamedTextColor.GRAY))
            return
        }
        val bodies = pending.map { s ->
            DialogBody.plainMessage(Component.text()
                .append(Component.text("${s.kind.label} from ${s.authorName}", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text(s.headline, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text(s.body, NamedTextColor.WHITE))
                .build(), 320)
        }
        val buttons = pending.flatMap { s ->
            listOf(
                action("Print \"${s.headline.take(18)}\"") { p, _ -> moderate(p, s.id, true) },
                action("Reject") { p, _ -> moderate(p, s.id, false) },
            )
        }
        player.showDialog(dialog("Submissions (${store.pending().size} waiting)", bodies, emptyList(), buttons, columns = 2))
    }

    fun moderate(player: Player, id: String, approve: Boolean) {
        val result = store.moderate(id, approve)
        player.sendMessage(when {
            result == null -> Component.text("That submission has already been dealt with.", NamedTextColor.GRAY)
            approve -> Component.text("\"${result.headline}\" will be printed in the next issue.", NamedTextColor.GREEN)
            else -> Component.text("Rejected \"${result.headline}\".", NamedTextColor.YELLOW)
        })
        result?.let { s ->
            Bukkit.getPlayer(java.util.UUID.fromString(s.authorUuid))?.takeIf { it != player }?.sendMessage(
                if (approve) Component.text("The editor accepted your ${s.kind.label.lowercase()} \"${s.headline}\".", NamedTextColor.GREEN)
                else Component.text("The editor didn't print your ${s.kind.label.lowercase()} \"${s.headline}\" this time.", NamedTextColor.GRAY)
            )
        }
        if (store.pending().isNotEmpty()) openModeration(player) else player.closeDialog()
    }

    private fun notifyEditors(message: Component) {
        Bukkit.getOnlinePlayers().filter { it.hasPermission("chronicler.admin") }.forEach { it.sendMessage(message) }
    }

    private fun action(label: String, onClick: (Player, DialogResponseView) -> Unit): ActionButton =
        ActionButton.builder(Component.text(label))
            .width(150)
            .action(DialogAction.customClick({ view, audience -> (audience as? Player)?.let { onClick(it, view) } }, CALLBACK))
            .build()

    private fun dialog(title: String, bodies: List<DialogBody>, inputs: List<DialogInput>, actions: List<ActionButton>, columns: Int = 1): Dialog =
        Dialog.create { factory ->
            factory.empty()
                .base(DialogBase.builder(Component.text(title)).canCloseWithEscape(true).pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE).body(bodies).inputs(inputs).build())
                .type(DialogType.multiAction(actions).columns(columns)
                    .exitAction(ActionButton.builder(Component.text("Cancel")).width(100).build()).build())
        }

    private companion object {
        val CALLBACK: ClickCallback.Options = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofHours(1))
            .build()
    }
}
