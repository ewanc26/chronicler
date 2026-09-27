package uk.ewancroft.chronicler.integration

import org.bukkit.entity.Player
import org.geysermc.cumulus.form.CustomForm
import uk.ewancroft.chronicler.contrib.ContributionScreens
import uk.ewancroft.chronicler.contrib.Contributions
import uk.ewancroft.chronicler.contrib.SubmissionKind

/** Writing to the paper and voting, as Bedrock forms. Only instantiated when Floodgate is present. */
class BedrockContributions(
    private val floodgate: FloodgateHook,
    private val store: Contributions,
    private val screens: ContributionScreens,
) {

    fun openWrite(player: Player) {
        val form = CustomForm.builder()
            .title("Write to the paper")
            .dropdown("What are you sending?", SubmissionKind.entries.map { it.label })
            .input("Headline", "A short headline", "")
            .input("Your words", "Write your letter or advert", "")
            .validResultHandler { response ->
                val kind = SubmissionKind.entries[response.asDropdown(0)]
                screens.submit(player, kind, response.asInput(1).orEmpty(), response.asInput(2).orEmpty())
            }
        floodgate.send(player.uniqueId, form)
    }

    fun openPoll(player: Player): Boolean {
        val poll = store.poll() ?: return false
        val current = poll.votes[player.uniqueId.toString()] ?: 0
        val form = CustomForm.builder()
            .title("Reader Poll")
            .dropdown(poll.question, poll.options, current)
            .validResultHandler { response ->
                val choice = response.asDropdown(0)
                if (store.vote(player.uniqueId.toString(), choice)) {
                    player.sendMessage("§aVote recorded: ${poll.options[choice]}.")
                }
            }
        floodgate.send(player.uniqueId, form)
        return true
    }
}
