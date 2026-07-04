package uk.ewancroft.chronicler.llm

import java.util.logging.Logger

class FakeLlmProvider(
    private val headline: String = "Fake Headline",
    private val body: String = "Fake article body for testing.",
    private val available: Boolean = true,
    val capturedSystemPrompts: MutableList<String> = mutableListOf(),
    val capturedSectionTitles: MutableList<String> = mutableListOf(),
    val capturedEventSummaries: MutableList<String> = mutableListOf(),
) : LlmProvider {

    private var callCount = 0
    private var failOnCall: Int? = null
    private var alwaysFail = false

    fun failOn(n: Int) { failOnCall = n }
    fun failOnAll() { alwaysFail = true }

    override fun generate(systemPrompt: String, sectionTitle: String, eventSummary: String): ArticleResult? {
        capturedSystemPrompts.add(systemPrompt)
        capturedSectionTitles.add(sectionTitle)
        capturedEventSummaries.add(eventSummary)
        callCount++
        if (alwaysFail) return null
        if (failOnCall == callCount) return null
        return ArticleResult(headline, body)
    }

    override fun isAvailable(): Boolean = available
    override fun name(): String = "fake"
}
