package uk.ewancroft.chronicler.publish

/**
 * AT Protocol timestamp identifiers (TIDs): 64-bit, 53 bits of microseconds
 * since the epoch then a 10-bit clock id, in base32-sortable, 13 characters.
 */
object Tid {
    private const val ALPHABET = "234567abcdefghijklmnopqrstuvwxyz"
    private var last = 0L

    @Synchronized
    fun next(nowMicros: Long = System.currentTimeMillis() * 1000, clockId: Int = (Math.random() * 1024).toInt()): String {
        val micros = maxOf(nowMicros, last + 1)
        last = micros
        return encode((micros shl 10) or (clockId.toLong() and 0x3FF))
    }

    fun encode(value: Long): String {
        val out = CharArray(13)
        var v = value
        for (i in 12 downTo 0) {
            out[i] = ALPHABET[(v and 31).toInt()]
            v = v ushr 5
        }
        return String(out)
    }
}
