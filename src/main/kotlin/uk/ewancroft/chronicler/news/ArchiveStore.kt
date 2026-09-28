package uk.ewancroft.chronicler.news

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Logger
import uk.ewancroft.chronicler.util.writeAtomically

class ArchiveStore(
    private val archiveDir: Path,
    private val retention: Int = Int.MAX_VALUE,
    private val logger: Logger = Logger.getLogger(ArchiveStore::class.java.name),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val archives = mutableListOf<Newspaper>()

    fun archive(newspaper: Newspaper) {
        synchronized(archives) {
            archives.add(newspaper)
            try {
                archiveDir.resolve("issue-${newspaper.issueNumber}.json").writeAtomically(json.encodeToString(newspaper))
                prune()
            } catch (e: Exception) {
                logger.warning("Failed to archive issue #${newspaper.issueNumber}: ${e.message}")
            }
        }
    }

    fun getAll(): List<Newspaper> {
        synchronized(archives) {
            return archives.toList().sortedByDescending { it.issueNumber }
        }
    }

    fun getIssue(number: Int): Newspaper? {
        synchronized(archives) {
            return archives.find { it.issueNumber == number }
                ?: loadIssue(number)
        }
    }

    fun latest(n: Int = 10): List<Newspaper> {
        return getAll().take(n)
    }

    fun loadAll() {
        if (Files.exists(archiveDir)) {
            try {
                val files = archiveDir.toFile().listFiles { f -> f.name.endsWith(".json") }
                    ?.sortedBy { it.name }
                if (files != null) {
                    synchronized(archives) {
                        archives.clear()
                        for (file in files) {
                            try {
                                val text = file.readText()
                                val newspaper: Newspaper = json.decodeFromString(text)
                                archives.add(newspaper)
                            } catch (e: Exception) {
                                logger.warning("Skipping unreadable archive ${file.name}: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warning("Failed to load archive directory $archiveDir: ${e.message}")
            }
        }
    }

    private fun loadIssue(number: Int): Newspaper? {
        return try {
            val file = archiveDir.resolve("issue-$number.json")
            if (Files.exists(file)) {
                val text = file.toFile().readText()
                json.decodeFromString<Newspaper>(text)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun exportIssue(number: Int, destination: Path): Boolean {
        val source = archiveDir.resolve("issue-$number.json")
        if (!Files.exists(source)) return false
        Files.createDirectories(destination.parent)
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    fun importIssue(source: Path): Newspaper? {
        return try {
            val newspaper = json.decodeFromString<Newspaper>(Files.readString(source))
            synchronized(archives) {
                if (archives.any { it.issueNumber == newspaper.issueNumber } ||
                    Files.exists(archiveDir.resolve("issue-${newspaper.issueNumber}.json"))) {
                    logger.warning("Refusing to import issue #${newspaper.issueNumber}: an archive with that number already exists.")
                    return null
                }
            }
            archive(newspaper)
            newspaper
        } catch (e: Exception) {
            logger.warning("Failed to import archive from $source: ${e.message}")
            null
        }
    }

    private fun prune() {
        val expired = archives.sortedByDescending { it.issueNumber }.drop(retention)
        expired.forEach { issue ->
            archives.remove(issue)
            Files.deleteIfExists(archiveDir.resolve("issue-${issue.issueNumber}.json"))
            logger.info("Pruned archived issue #${issue.issueNumber} (retention: $retention).")
        }
    }
}
