package uk.ewancroft.chronicler.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TextTest {

    @Test
    fun `plain flattens styled and nested components`() {
        val component = Component.text("Hello ", NamedTextColor.GOLD)
            .append(Component.text("world").append(Component.text("!")))
        assertEquals("Hello world!", component.plain())
    }

    @Test
    fun `plain never returns the component debug representation`() {
        val text = Component.text("Welcome to the server").plain()
        assertEquals("Welcome to the server", text)
    }
}
