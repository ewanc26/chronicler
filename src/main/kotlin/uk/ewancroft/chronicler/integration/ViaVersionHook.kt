package uk.ewancroft.chronicler.integration

import com.viaversion.viaversion.api.Via
import java.util.UUID

/** Only loaded when ViaVersion is enabled. */
class ViaVersionHook {
    private val api = Via.getAPI()

    /**
     * True for clients older than the server's own version. Those cannot load
     * the pack (format and item-model changes) and, below 1.21.6, see dialogs
     * as chest menus via ViaBackwards, which would mangle a printed page.
     */
    fun isOlderThanServer(uuid: UUID): Boolean {
        val client = api.getPlayerProtocolVersion(uuid) ?: return false
        val server = api.serverVersion.highestSupportedProtocolVersion()
        return client.olderThan(server)
    }
}
