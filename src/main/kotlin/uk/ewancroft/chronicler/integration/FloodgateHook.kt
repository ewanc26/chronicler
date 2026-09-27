package uk.ewancroft.chronicler.integration

import org.geysermc.cumulus.form.util.FormBuilder
import org.geysermc.floodgate.api.FloodgateApi
import java.util.UUID

/** Only loaded when Floodgate is enabled. */
class FloodgateHook {
    private val api = FloodgateApi.getInstance()

    fun isBedrock(uuid: UUID): Boolean = api.isFloodgatePlayer(uuid)

    fun send(uuid: UUID, form: FormBuilder<*, *, *>): Boolean = api.sendForm(uuid, form)
}
