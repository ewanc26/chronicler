package uk.ewancroft.chronicler.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import java.util.Locale

/**
 * Flattens an Adventure component to plain text for storage in event details.
 * Translatable components (death messages, advancement titles) are rendered
 * through the server's global translator first so they read as English text
 * rather than raw translation keys; `Component.toString()` must never be used
 * here, as it yields the component's debug representation.
 */
fun Component.plain(locale: Locale = Locale.US): String {
    val rendered = try {
        GlobalTranslator.render(this, locale)
    } catch (_: Throwable) {
        this
    }
    return PlainTextComponentSerializer.plainText().serialize(rendered)
}
