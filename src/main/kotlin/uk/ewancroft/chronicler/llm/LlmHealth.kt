package uk.ewancroft.chronicler.llm

/**
 * Tracks whether the configured LLM provider is reachable. [refresh] performs
 * blocking network I/O and must only be called off the server thread; readers
 * such as status commands and placeholders use the cached [available] value.
 */
class LlmHealth(private val provider: LlmProvider) {

    @Volatile
    var available: Boolean = false
        private set

    fun refresh(): Boolean = provider.isAvailable().also { available = it }
}
