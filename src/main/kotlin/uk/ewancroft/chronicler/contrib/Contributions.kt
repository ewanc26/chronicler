package uk.ewancroft.chronicler.contrib

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.ewancroft.chronicler.news.NewspaperSection
import uk.ewancroft.chronicler.news.PollOption
import uk.ewancroft.chronicler.news.PollResult
import uk.ewancroft.chronicler.news.Story
import uk.ewancroft.chronicler.util.quarantineCorrupt
import uk.ewancroft.chronicler.util.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

@Serializable
enum class SubmissionKind(val section: String, val label: String) {
    LETTER("Letters to the Editor", "Letter to the editor"),
    CLASSIFIED("Classifieds", "Classified advert"),
}

@Serializable
enum class SubmissionStatus { PENDING, APPROVED, REJECTED, PRINTED }

@Serializable
data class Submission(
    val id: String,
    val kind: SubmissionKind,
    val authorName: String,
    val authorUuid: String,
    val headline: String,
    val body: String,
    val submittedAt: Long,
    val status: SubmissionStatus = SubmissionStatus.PENDING,
)

@Serializable
data class Poll(
    val id: String,
    val question: String,
    val options: List<String>,
    /** Player UUID to chosen option index; a player may change their vote until the poll is printed. */
    val votes: Map<String, Int> = emptyMap(),
    val createdAt: Long,
)

@Serializable
private data class ContributionData(
    val submissions: List<Submission> = emptyList(),
    val poll: Poll? = null,
)

data class ContributionLimits(
    val requireApproval: Boolean = true,
    val maxPendingPerPlayer: Int = 2,
    val headlineLength: Int = 60,
    val letterLength: Int = 600,
    val classifiedLength: Int = 240,
)

/**
 * Letters to the editor, classified adverts and the reader poll: stored in
 * contributions.json, moderated by staff, printed in the next issue.
 */
class Contributions(
    private val file: Path,
    private val limits: ContributionLimits,
    private val logger: Logger? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private var data = ContributionData()

    sealed interface Result {
        data class Accepted(val submission: Submission) : Result
        data class Rejected(val reason: String) : Result
    }

    @Synchronized
    fun load() {
        if (!Files.exists(file)) return
        data = try {
            json.decodeFromString(Files.readString(file))
        } catch (e: Exception) {
            file.quarantineCorrupt(logger, e)
            ContributionData()
        }
    }

    @Synchronized
    private fun save() {
        file.writeAtomically(json.encodeToString(ContributionData.serializer(), data))
    }

    @Synchronized
    fun submit(kind: SubmissionKind, authorName: String, authorUuid: String, headline: String, body: String, now: Long = System.currentTimeMillis()): Result {
        val cleanHeadline = clean(headline, singleLine = true)
        val cleanBody = clean(body, singleLine = false)
        val maxBody = if (kind == SubmissionKind.LETTER) limits.letterLength else limits.classifiedLength
        when {
            cleanHeadline.isEmpty() -> return Result.Rejected("Give it a headline.")
            cleanBody.isEmpty() -> return Result.Rejected("There's nothing to print yet.")
            cleanHeadline.length > limits.headlineLength -> return Result.Rejected("Keep the headline under ${limits.headlineLength} characters.")
            cleanBody.length > maxBody -> return Result.Rejected("Keep it under $maxBody characters.")
        }
        val pending = data.submissions.count { it.authorUuid == authorUuid && it.status == SubmissionStatus.PENDING }
        if (limits.requireApproval && pending >= limits.maxPendingPerPlayer) {
            return Result.Rejected("You already have $pending submission(s) waiting for the editor.")
        }
        val submission = Submission(
            id = UUID.randomUUID().toString().substring(0, 8),
            kind = kind,
            authorName = authorName,
            authorUuid = authorUuid,
            headline = cleanHeadline,
            body = cleanBody,
            submittedAt = now,
            status = if (limits.requireApproval) SubmissionStatus.PENDING else SubmissionStatus.APPROVED,
        )
        data = data.copy(submissions = data.submissions + submission)
        save()
        return Result.Accepted(submission)
    }

    /**
     * Reverts a story's contribution to non-printing state when an editor
     * removes it from a draft, so it does not silently reappear (already
     * APPROVED, hence eligible again) in a later issue with no further
     * editor action. Unlike [moderate], which only acts on PENDING items,
     * this acts on the APPROVED/poll-open state a draft pulls from.
     */
    @Synchronized
    fun withdraw(sourceId: String): Boolean = when {
        sourceId.startsWith("submission:") -> {
            val id = sourceId.removePrefix("submission:")
            val target = data.submissions.firstOrNull { it.id == id && it.status == SubmissionStatus.APPROVED }
            if (target == null) false else {
                data = data.copy(submissions = data.submissions.map { if (it.id == id) it.copy(status = SubmissionStatus.REJECTED) else it })
                save()
                true
            }
        }
        sourceId.startsWith("poll:") -> {
            val poll = data.poll
            if (poll == null || "poll:${poll.id}" != sourceId) false else {
                data = data.copy(poll = null)
                save()
                true
            }
        }
        else -> false
    }

    @Synchronized
    fun printedCount(authorUuid: String): Long =
        data.submissions.count { it.authorUuid == authorUuid && it.status == SubmissionStatus.PRINTED }.toLong()

    @Synchronized
    fun pending(): List<Submission> = data.submissions.filter { it.status == SubmissionStatus.PENDING }

    @Synchronized
    fun approved(): List<Submission> = data.submissions.filter { it.status == SubmissionStatus.APPROVED }

    @Synchronized
    fun moderate(id: String, approve: Boolean): Submission? {
        val target = data.submissions.firstOrNull { it.id == id && it.status == SubmissionStatus.PENDING } ?: return null
        val updated = target.copy(status = if (approve) SubmissionStatus.APPROVED else SubmissionStatus.REJECTED)
        data = data.copy(submissions = data.submissions.map { if (it.id == id) updated else it })
        save()
        return updated
    }

    // ---- Poll ----------------------------------------------------------------

    @Synchronized
    fun poll(): Poll? = data.poll

    /** Starts a poll; returns why it could not, or null on success. */
    @Synchronized
    fun createPoll(question: String, options: List<String>, now: Long = System.currentTimeMillis()): String? {
        val q = clean(question, singleLine = true)
        val opts = options.map { clean(it, singleLine = true) }.filter { it.isNotEmpty() }.distinct()
        when {
            data.poll != null -> return "A poll is already running; it closes when the next issue is printed."
            q.isEmpty() -> return "Ask a question."
            opts.size !in 2..6 -> return "Give between 2 and 6 options."
        }
        data = data.copy(poll = Poll(UUID.randomUUID().toString().substring(0, 8), q.take(120), opts.map { it.take(40) }, createdAt = now))
        save()
        return null
    }

    @Synchronized
    fun vote(playerUuid: String, option: Int): Boolean {
        val poll = data.poll ?: return false
        if (option !in poll.options.indices) return false
        data = data.copy(poll = poll.copy(votes = poll.votes + (playerUuid to option)))
        save()
        return true
    }

    @Synchronized
    fun cancelPoll(): Boolean {
        if (data.poll == null) return false
        data = data.copy(poll = null)
        save()
        return true
    }

    // ---- Printing --------------------------------------------------------------

    /** Sections for the next issue: approved letters and classifieds, and the poll results. */
    @Synchronized
    fun sectionsForPrint(): List<NewspaperSection> {
        val sections = mutableListOf<NewspaperSection>()
        for (kind in SubmissionKind.entries) {
            val items = approved().filter { it.kind == kind }
            if (items.isEmpty()) continue
            sections += NewspaperSection(kind.section, items.map { s ->
                Story(
                    headline = s.headline,
                    body = s.body,
                    players = listOf(s.authorName),
                    eventType = null,
                    byline = if (kind == SubmissionKind.LETTER) "A letter from ${s.authorName}" else "Placed by ${s.authorName}",
                    sourceId = "submission:${s.id}",
                )
            })
        }
        data.poll?.takeIf { it.votes.isNotEmpty() }?.let { poll ->
            val counts = poll.options.indices.map { i -> poll.votes.values.count { it == i } }
            val total = counts.sum()
            val body = poll.options.zip(counts).joinToString("; ") { (label, n) ->
                "$label: $n vote${if (n == 1) "" else "s"} (${if (total == 0) 0 else n * 100 / total}%)"
            } + ". $total reader${if (total == 1) "" else "s"} voted."
            sections += NewspaperSection("Reader Poll", listOf(Story(
                headline = poll.question,
                body = body,
                players = emptyList(),
                eventType = null,
                byline = "Chronicler Poll Desk",
                sourceId = "poll:${poll.id}",
                poll = PollResult(poll.options.zip(counts).map { (label, n) -> PollOption(label, n) }),
            )))
        }
        return sections
    }

    /** Marks everything an issue carried as printed and closes a printed poll. */
    @Synchronized
    fun markPrinted(sourceIds: Set<String>) {
        if (sourceIds.isEmpty()) return
        val printed = sourceIds.filter { it.startsWith("submission:") }.map { it.removePrefix("submission:") }.toSet()
        val pollPrinted = data.poll?.let { "poll:${it.id}" in sourceIds } == true
        data = data.copy(
            submissions = data.submissions.map { if (it.id in printed) it.copy(status = SubmissionStatus.PRINTED) else it }
                // Keep a short history of printed and rejected items only.
                .let { all -> all.filter { it.status == SubmissionStatus.PENDING || it.status == SubmissionStatus.APPROVED } + all.filter { it.status == SubmissionStatus.PRINTED || it.status == SubmissionStatus.REJECTED }.takeLast(100) },
            poll = if (pollPrinted) null else data.poll,
        )
        save()
    }

    private fun clean(text: String, singleLine: Boolean): String {
        // Strip legacy colour codes and control characters; players write plain text.
        var t = text.replace(Regex("[§&][0-9a-fk-orA-FK-OR]"), "").filter { it == '\n' || !it.isISOControl() }
        t = if (singleLine) t.replace('\n', ' ') else t.replace(Regex("\n{3,}"), "\n\n")
        return t.replace(Regex("[ \t]+"), " ").trim()
    }
}
