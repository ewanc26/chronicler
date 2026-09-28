package uk.ewancroft.chronicler.integration

import com.djrapitops.plan.extension.CallEvents
import com.djrapitops.plan.extension.DataExtension
import com.djrapitops.plan.extension.ExtensionService
import com.djrapitops.plan.extension.annotation.NumberProvider
import com.djrapitops.plan.extension.annotation.PluginInfo
import com.djrapitops.plan.extension.icon.Color
import com.djrapitops.plan.extension.icon.Family
import java.util.UUID
import java.util.logging.Logger

/** The numbers Chronicler exposes to Plan; supplied as functions so the extension holds no plugin state. */
class PlanData(
    val issuesPublished: () -> Long,
    val printedReaders: () -> Long,
    val pendingSubmissions: () -> Long,
    val storiesFeaturing: (String) -> Long,
    val contributionsPrinted: (UUID) -> Long,
)

/** Only loaded when Plan is enabled: shows Chronicler on Plan's server and player pages. */
@PluginInfo(name = "Chronicler", iconName = "newspaper", iconFamily = Family.SOLID, color = Color.BROWN)
class PlanExtension(private val data: PlanData) : DataExtension {

    override fun callExtensionMethodsOn(): Array<CallEvents> =
        arrayOf(CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.SERVER_PERIODICAL)

    @NumberProvider(text = "Issues published", description = "Issues of the server newspaper so far", iconName = "newspaper", iconColor = Color.BROWN, priority = 100)
    fun issuesPublished(): Long = data.issuesPublished()

    @NumberProvider(text = "Reading the printed edition", description = "Online players with the newspaper resource pack loaded", iconName = "book-open", iconColor = Color.AMBER, priority = 90)
    fun printedReaders(): Long = data.printedReaders()

    @NumberProvider(text = "Letters awaiting the editor", description = "Reader submissions pending approval", iconName = "envelope", iconColor = Color.GREY, priority = 80)
    fun pendingSubmissions(): Long = data.pendingSubmissions()

    @NumberProvider(text = "Stories featured in", description = "Archived stories that mention this player", iconName = "newspaper", iconColor = Color.BROWN, priority = 100, showInPlayerTable = true)
    fun storiesFeatured(playerName: String): Long = data.storiesFeaturing(playerName)

    @NumberProvider(text = "Letters and adverts printed", description = "Reader contributions by this player that made it into print", iconName = "pen-nib", iconColor = Color.AMBER, priority = 90)
    fun contributionsPrinted(playerUUID: UUID): Long = data.contributionsPrinted(playerUUID)

    companion object {
        fun register(data: PlanData, logger: Logger): PlanExtension? = try {
            PlanExtension(data).also { ExtensionService.getInstance().register(it) }
        } catch (e: Throwable) {
            logger.warning("Could not register the Plan extension (${e.javaClass.simpleName}: ${e.message}).")
            null
        }

        fun unregister(extension: PlanExtension) = runCatching { ExtensionService.getInstance().unregister(extension) }
    }
}
