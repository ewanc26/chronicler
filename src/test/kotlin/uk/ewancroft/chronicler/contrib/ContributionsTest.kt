package uk.ewancroft.chronicler.contrib

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContributionsTest {
    @TempDir lateinit var dir: Path

    private fun store(requireApproval: Boolean = true) =
        Contributions(dir.resolve("contributions.json"), ContributionLimits(requireApproval = requireApproval)).apply { load() }

    @Test
    fun `letters wait for approval then print once`() {
        val c = store()
        val sub = assertIs<Contributions.Result.Accepted>(c.submit(SubmissionKind.LETTER, "Alex", "u1", "Fix the bridge", "The north bridge has a hole.")).submission
        assertTrue(c.sectionsForPrint().isEmpty(), "pending letters are not printed")
        assertNotNull(c.moderate(sub.id, approve = true))
        val sections = c.sectionsForPrint()
        assertEquals("Letters to the Editor", sections.single().title)
        val story = sections.single().stories.single()
        assertEquals("A letter from Alex", story.byline)
        c.markPrinted(setOf(story.sourceId!!))
        assertTrue(c.sectionsForPrint().isEmpty(), "printed letters are not printed again")
    }

    @Test
    fun `submissions are cleaned, limited and persisted`() {
        val c = store()
        val tooLong = c.submit(SubmissionKind.CLASSIFIED, "Alex", "u1", "Sale", "x".repeat(500))
        assertIs<Contributions.Result.Rejected>(tooLong)
        val ok = assertIs<Contributions.Result.Accepted>(c.submit(SubmissionKind.CLASSIFIED, "Alex", "u1", "§cFor   sale", "Iron   golem\u0007 farm"))
        assertEquals("For sale", ok.submission.headline)
        assertEquals("Iron golem farm", ok.submission.body)
        c.submit(SubmissionKind.LETTER, "Alex", "u1", "Two", "Second")
        assertIs<Contributions.Result.Rejected>(c.submit(SubmissionKind.LETTER, "Alex", "u1", "Three", "Third"), "pending limit")
        assertEquals(2, store().pending().size, "reloaded from disk")
    }

    @Test
    fun `auto-approval prints without moderation`() {
        val c = store(requireApproval = false)
        c.submit(SubmissionKind.CLASSIFIED, "Steve", "u2", "Wanted", "Diamonds.")
        assertEquals("Classifieds", c.sectionsForPrint().single().title)
    }

    @Test
    fun `polls count one changeable vote per player and close when printed`() {
        val c = store()
        assertNull(c.createPoll("Best biome?", listOf("Forest", "Desert", "Forest")))
        assertEquals(listOf("Forest", "Desert"), c.poll()!!.options)
        assertTrue(c.createPoll("Another?", listOf("a", "b")) != null, "only one poll at a time")
        c.vote("u1", 0); c.vote("u2", 1); c.vote("u1", 1)
        val story = c.sectionsForPrint().single { it.title == "Reader Poll" }.stories.single()
        assertEquals(listOf(0, 2), story.poll!!.options.map { it.votes })
        c.markPrinted(setOf(story.sourceId!!))
        assertNull(c.poll())
    }

    @Test
    fun `withdrawing a submission pulled from the draft keeps it out of future issues`() {
        val c = store()
        val sub = assertIs<Contributions.Result.Accepted>(c.submit(SubmissionKind.LETTER, "Alex", "u1", "Fix the bridge", "It has a hole.")).submission
        c.moderate(sub.id, approve = true)
        val sourceId = c.sectionsForPrint().single().stories.single().sourceId!!

        assertTrue(c.withdraw(sourceId), "an editor pulling an approved story out of the draft")
        assertTrue(c.sectionsForPrint().isEmpty(), "withdrawn letters must not reappear unattended in a later issue")
        // A second withdrawal of the same, already-withdrawn story is a no-op, not an error.
        assertFalse(c.withdraw(sourceId))
    }

    @Test
    fun `withdrawing the poll cancels it without printing partial results`() {
        val c = store()
        c.createPoll("Best biome?", listOf("Forest", "Desert"))
        c.vote("u1", 0)
        val sourceId = c.sectionsForPrint().single { it.title == "Reader Poll" }.stories.single().sourceId!!

        assertTrue(c.withdraw(sourceId))
        assertNull(c.poll())
        assertTrue(c.sectionsForPrint().isEmpty())
    }

    @Test
    fun `withdraw ignores unknown or already-printed sourceIds`() {
        val c = store()
        assertFalse(c.withdraw("submission:doesnotexist"))
        assertFalse(c.withdraw("poll:doesnotexist"))
        assertFalse(c.withdraw("not-a-recognised-prefix"))
    }
}
