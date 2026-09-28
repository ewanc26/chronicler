package uk.ewancroft.chronicler.util

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Writes [text] to a sibling temporary file and moves it over this path, so a
 * crash or full disk mid-write never leaves a truncated store behind.
 */
fun Path.writeAtomically(text: String) = writeAtomically(text.toByteArray(StandardCharsets.UTF_8))

fun Path.writeAtomically(bytes: ByteArray) {
    val parent = toAbsolutePath().parent
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, "$fileName.", ".tmp")
    try {
        Files.write(temporary, bytes)
        try {
            Files.move(temporary, this, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, this, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/**
 * Moves an unreadable store aside as `<name>.corrupt-<millis>` so the next save
 * does not overwrite data an operator could still recover by hand.
 */
fun Path.quarantineCorrupt(logger: Logger?, cause: Exception): Path? {
    val target = resolveSibling("$fileName.corrupt-${System.currentTimeMillis()}")
    return try {
        Files.move(this, target)
        val reason = cause.message?.lineSequence()?.firstOrNull()?.take(120) ?: cause.javaClass.simpleName
        logger?.log(Level.WARNING, "Could not read $fileName ($reason); moved it to ${target.fileName} and started empty.")
        target
    } catch (e: Exception) {
        logger?.log(Level.SEVERE, "Could not read or move aside $fileName; it may be overwritten on next save.", e)
        null
    }
}
