package uk.ewancroft.chronicler.contrib

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import uk.ewancroft.chronicler.integration.BedrockContributions
import uk.ewancroft.chronicler.integration.ClientKind
import uk.ewancroft.chronicler.integration.ClientSupport

/**
 * /chronicler write, poll and submissions. Modern clients get dialogs, Bedrock
 * players get forms, and everyone (older clients, the console) can use the
 * text form of each command.
 */
class ContributionCommands(
    private val store: Contributions,
    private val screens: ContributionScreens,
    private val clients: ClientSupport,
    private val bedrock: BedrockContributions?,
) {

    fun write(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("chronicler.write")) return deny(sender)
        val player = sender as? Player ?: return say(sender, "Only players can write to the paper.")
        if (args.isEmpty()) {
            when (clients.kind(player)) {
                ClientKind.MODERN -> return screens.openWrite(player)
                ClientKind.BEDROCK -> if (bedrock != null) return bedrock.openWrite(player)
                ClientKind.LEGACY_JAVA -> {}
            }
            return say(sender, "Usage: /chronicler write <letter|ad> <headline> | <your words>")
        }
        val kind = when (args[0].lowercase()) {
            "letter" -> SubmissionKind.LETTER
            "ad", "advert", "classified" -> SubmissionKind.CLASSIFIED
            else -> return say(sender, "Usage: /chronicler write <letter|ad> <headline> | <your words>")
        }
        val text = args.drop(1).joinToString(" ")
        if ('|' !in text) return say(sender, "Separate the headline from your words with |")
        screens.submit(player, kind, text.substringBefore('|'), text.substringAfter('|'))
    }

    fun poll(sender: CommandSender, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase()
        if (sub in setOf("create", "cancel", "results") && !sender.hasPermission("chronicler.admin")) return deny(sender)
        when (sub) {
            null -> {
                val player = sender as? Player ?: return pollText(sender)
                when (clients.kind(player)) {
                    ClientKind.MODERN -> screens.openPoll(player)
                    ClientKind.BEDROCK -> if (bedrock?.openPoll(player) != true) pollText(sender)
                    ClientKind.LEGACY_JAVA -> pollText(sender)
                }
            }
            "vote" -> {
                val player = sender as? Player ?: return say(sender, "Only players can vote.")
                val poll = store.poll() ?: return say(sender, "There is no reader poll running right now.")
                val choice = args.getOrNull(1)?.toIntOrNull()?.minus(1)
                if (choice == null || !store.vote(player.uniqueId.toString(), choice)) {
                    return say(sender, "Vote with /chronicler poll vote <1-${poll.options.size}>")
                }
                sender.sendMessage(Component.text("Vote recorded: ${poll.options[choice]}.", NamedTextColor.GREEN))
            }
            "create" -> {
                val text = args.drop(1).joinToString(" ")
                if (text.isBlank() && sender is Player && clients.kind(sender) == ClientKind.MODERN) return screens.openCreatePoll(sender)
                val parts = text.split('|').map { it.trim() }
                val error = store.createPoll(parts.first(), parts.drop(1))
                    ?: return sender.sendMessage(Component.text("Poll started. Players can vote with /chronicler poll.", NamedTextColor.GREEN))
                say(sender, "$error Usage: /chronicler poll create <question> | <option> | <option>...")
            }
            "cancel" -> say(sender, if (store.cancelPoll()) "Poll cancelled; nothing will be printed." else "There is no poll running.")
            "results" -> pollText(sender, withCounts = true)
            else -> say(sender, "Usage: /chronicler poll [vote <n>|create|cancel|results]")
        }
    }

    fun submissions(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("chronicler.admin")) return deny(sender)
        val action = args.firstOrNull()?.lowercase()
        if (action == "approve" || action == "reject") {
            val id = args.getOrNull(1) ?: return say(sender, "Usage: /chronicler submissions $action <id>")
            if (sender is Player) return screens.moderate(sender, id, action == "approve")
            val result = store.moderate(id, action == "approve")
            return say(sender, if (result == null) "No pending submission $id." else "${action.replaceFirstChar { it.uppercase() }}d \"${result.headline}\".")
        }
        if (sender is Player && clients.kind(sender) == ClientKind.MODERN) return screens.openModeration(sender)
        val pending = store.pending()
        if (pending.isEmpty()) return say(sender, "Nothing is waiting for the editor.")
        sender.sendMessage(Component.text("${pending.size} submission(s) waiting:", NamedTextColor.GOLD))
        pending.forEach { s ->
            sender.sendMessage(Component.text(" [${s.id}] ${s.kind.label} from ${s.authorName}: \"${s.headline}\" — ${s.body.take(80)}", NamedTextColor.GRAY))
        }
        say(sender, "Use /chronicler submissions approve|reject <id>")
    }

    private fun pollText(sender: CommandSender, withCounts: Boolean = false) {
        val poll = store.poll() ?: return say(sender, "There is no reader poll running right now.")
        sender.sendMessage(Component.text("Reader Poll: ${poll.question}", NamedTextColor.GOLD))
        poll.options.forEachIndexed { i, option ->
            val count = if (withCounts) " — ${poll.votes.values.count { it == i }} vote(s)" else ""
            sender.sendMessage(Component.text(" ${i + 1}. $option$count", NamedTextColor.GRAY))
        }
        say(sender, "Vote with /chronicler poll vote <number>")
    }

    private fun say(sender: CommandSender, text: String) = sender.sendMessage(Component.text(text, NamedTextColor.GRAY))

    private fun deny(sender: CommandSender) = sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED))
}
