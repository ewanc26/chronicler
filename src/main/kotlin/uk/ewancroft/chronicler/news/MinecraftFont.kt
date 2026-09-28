package uk.ewancroft.chronicler.news

/**
 * Pixel advances of the vanilla default font, measured from the 26.1 client's
 * bitmap glyphs (advance = glyph width + 1px spacing). Used to lay text out the
 * way the client will, since books clip rather than scroll overflowing lines.
 */
object MinecraftFont {

    private val advances: Map<Char, Int> = buildMap {
        fun set(width: Int, chars: String) = chars.forEach { put(it, width) }
        set(2, "!',.:;i|·")
        set(3, "`l‘’•")
        set(4, " \"()*I[]t{}")
        set(5, "<>fk“”")
        set(7, "@~–«»€")
        set(8, "…©")
        set(9, "—")
    }

    /** Most letters, digits and symbols are 5px wide plus spacing. */
    private const val DEFAULT_ADVANCE = 6

    /** Characters outside the table are drawn by fallback fonts; assume the widest common case. */
    private const val UNKNOWN_ADVANCE = 8

    fun advance(char: Char, bold: Boolean = false): Int {
        val base = advances[char] ?: if (char.code in 32..126 || char in "£é") DEFAULT_ADVANCE else UNKNOWN_ADVANCE
        return base + if (bold && char != ' ') 1 else 0
    }

    fun width(text: String, bold: Boolean = false): Int = text.sumOf { advance(it, bold) }

    /**
     * Greedy word wrap matching the client: words move to the next line when
     * they would exceed [maxWidth], and words wider than a whole line are split.
     */
    fun wrap(text: String, maxWidth: Int, bold: Boolean = false): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            var lineWidth = 0
            for (rawWord in paragraph.split(' ').filter { it.isNotEmpty() }) {
                var word = rawWord
                val spaceWidth = if (line.isEmpty()) 0 else advance(' ', bold)
                val wordWidth = width(word, bold)
                if (lineWidth + spaceWidth + wordWidth <= maxWidth) {
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(word)
                    lineWidth += spaceWidth + wordWidth
                    continue
                }
                if (line.isNotEmpty()) {
                    lines.add(line.toString())
                    line = StringBuilder()
                    lineWidth = 0
                }
                while (width(word, bold) > maxWidth) {
                    var cut = word.length
                    while (cut > 1 && width(word.substring(0, cut), bold) > maxWidth) cut--
                    lines.add(word.substring(0, cut))
                    word = word.substring(cut)
                }
                line.append(word)
                lineWidth = width(word, bold)
            }
            lines.add(line.toString())
        }
        return lines
    }
}
