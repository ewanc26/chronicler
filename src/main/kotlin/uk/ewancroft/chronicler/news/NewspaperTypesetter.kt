package uk.ewancroft.chronicler.news

import uk.ewancroft.chronicler.config.NewspaperConfig
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Date

/**
 * Typesets an issue as broadsheet pages: blackletter masthead, dateline, a
 * full-width lead headline and justified multi-column copy. Pages are plain
 * images; [NewspaperPack] slices them into font glyphs the client can draw.
 */
class NewspaperTypesetter(
    private val config: NewspaperConfig,
    private val fonts: NewspaperFonts = NewspaperFonts.bundled(),
) {

    companion object {
        const val PAGE_WIDTH = 1024
        const val PAGE_HEIGHT = 1536
        const val MAX_PAGES = 8

        private const val MARGIN = 40
        private const val COLUMNS = 3
        private const val GUTTER = 28
        private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
        private const val COLUMN_WIDTH = (CONTENT_WIDTH - (COLUMNS - 1) * GUTTER) / COLUMNS

        private const val BODY_SIZE = 21f
        private const val BODY_LEADING = 27

        val PAPER = Color(0xF1, 0xEA, 0xDA)
        val INK = Color(0x1B, 0x19, 0x16)
        val MUTED = Color(0x5A, 0x52, 0x47)

        /** All colours text and rules are drawn in, for palette quantization. */
        fun inks(accentColor: Int): List<Color> = listOf(INK, MUTED, Color(accentColor))
    }

    private val accent = Color(config.accentColor)
    private val muted = MUTED

    private val body = fonts.regular.deriveFont(BODY_SIZE)
    private val bodyItalic = fonts.italic.deriveFont(BODY_SIZE - 2f)
    private val smallCaps = fonts.bold.deriveFont(15f)
    private val byline = fonts.italic.deriveFont(16f)

    /** A slice of flowed content: fixed height, drawn at a column position. */
    private class Fragment(
        val height: Int,
        val keepWithNext: Boolean = false,
        val spacer: Boolean = false,
        val draw: (Graphics2D, Int, Int, Int) -> Unit,
    )

    /** Bottom edge of the columns most recently flowed, for trimming a short final page. */
    private var lastColumnBottom = 0

    private val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().also(::applyHints)

    /** Player faces for the issue being typeset, keyed by player name. */
    private var portraits: Map<String, BufferedImage> = emptyMap()

    fun typeset(newspaper: Newspaper, portraits: Map<String, BufferedImage> = emptyMap()): List<BufferedImage> {
        this.portraits = portraits
        val sections = newspaper.sections.filter { it.stories.isNotEmpty() }
        val lead = sections.firstOrNull()?.stories?.firstOrNull()
        val stories = sections.flatMap { section ->
            val remaining = if (section === sections.first()) section.stories.drop(1) else section.stories
            if (remaining.isEmpty()) emptyList() else sectionFragments(section, remaining)
        }
        val frontCopy = if (lead != null) leadBody(lead) + gap(18) + stories else stories

        val pages = mutableListOf<BufferedImage>()
        var queue = frontCopy
        var pageNumber = 1
        while (pageNumber == 1 || (queue.isNotEmpty() && pageNumber <= MAX_PAGES)) {
            val page = blankPage()
            val g = page.createGraphics().also(::applyHints)
            val top = if (pageNumber == 1) drawFrontHead(g, newspaper, lead) else drawRunningHead(g, newspaper, pageNumber)
            queue = flow(g, queue, top, PAGE_HEIGHT - MARGIN - 20, balanceLastPage = true)
            // A short final page (the front page too, for a small issue) is trimmed in
            // whole tiles rather than left mostly blank.
            val height = if (queue.isEmpty()) {
                val needed = lastColumnBottom + MARGIN + 30
                ((needed + NewspaperPack.TILE - 1) / NewspaperPack.TILE * NewspaperPack.TILE).coerceIn(NewspaperPack.TILE * 4, PAGE_HEIGHT)
            } else PAGE_HEIGHT
            drawFolio(g, newspaper, pageNumber, height)
            g.dispose()
            pages += if (height < PAGE_HEIGHT) page.getSubimage(0, 0, PAGE_WIDTH, height).let { sub ->
                BufferedImage(PAGE_WIDTH, height, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().drawImage(sub, 0, 0, null) }
            } else page
            pageNumber++
        }
        return pages
    }

    // ---- Page furniture ----------------------------------------------------

    private fun drawFrontHead(g: Graphics2D, newspaper: Newspaper, lead: Story?): Int {
        var y = MARGIN
        // Ears above the masthead
        g.font = smallCaps; g.color = muted
        g.drawString("PRICE: ONE EMERALD", MARGIN, y + 14)
        val right = (config.serverName.ifBlank { "Minecraft Edition" }).uppercase()
        g.drawString(right, PAGE_WIDTH - MARGIN - g.fontMetrics.stringWidth(right), y + 14)
        g.font = byline
        centre(g, config.titlePageText, y + 14)
        y += 26

        // Masthead: largest blackletter size that fits the measure.
        var size = 128f
        var masthead = fonts.blackletter.deriveFont(size)
        while (size > 40f && scratch.getFontMetrics(masthead).stringWidth(config.title) > CONTENT_WIDTH) {
            size -= 2f; masthead = fonts.blackletter.deriveFont(size)
        }
        g.font = masthead; g.color = INK
        val fm = g.fontMetrics
        y += fm.ascent
        centre(g, config.title, y)
        y += fm.descent + 8

        // Dateline between a thick and thin rule, as on a broadsheet.
        rule(g, y, 4f); y += 8; rule(g, y, 1f); y += 8
        g.font = smallCaps; g.color = INK
        val stories = newspaper.sections.sumOf { it.stories.size }
        val parts = listOf("NO. ${newspaper.issueNumber}", dateLine(newspaper.toTime).uppercase(), "$stories STORIES")
        g.drawString(parts[0], MARGIN, y + 15)
        centre(g, parts[1], y + 15)
        g.drawString(parts[2], PAGE_WIDTH - MARGIN - g.fontMetrics.stringWidth(parts[2]), y + 15)
        y += 24
        rule(g, y, 1f); y += 8; rule(g, y, 4f); y += 22

        if (lead != null) {
            // Lead headline across the full measure, shrunk to at most two lines.
            var headSize = 66f
            var lines: List<String>
            var font: Font
            do {
                font = fonts.bold.deriveFont(headSize)
                lines = wrap(lead.headline, g.getFontMetrics(font), CONTENT_WIDTH)
                headSize -= 2f
            } while (lines.size > 2 && headSize > 36f)
            g.font = font; g.color = INK
            val hm = g.fontMetrics
            for (line in lines.take(3)) {
                y += hm.ascent
                centre(g, line, y)
                y += hm.descent
            }
            y += 6
            g.font = byline; g.color = muted
            val credit = buildString {
                append(lead.credit)
                if (lead.players.isNotEmpty()) append(" — with ${lead.players.take(4).joinToString(", ")}")
            }
            centre(g, credit, y + 16)
            y += 30
            // A captioned strip of those involved, like press photographs.
            val faces = lead.players.mapNotNull { name -> portraits[name]?.let { name to it } }.take(6)
            if (faces.isNotEmpty()) {
                val size = 72
                val gap = 26
                val total = faces.size * size + (faces.size - 1) * gap
                var x = (PAGE_WIDTH - total) / 2
                for ((name, face) in faces) {
                    drawPortrait(g, face, x, y, size)
                    g.font = byline; g.color = muted
                    g.drawString(name, x + (size - g.fontMetrics.stringWidth(name)) / 2, y + size + 18)
                    x += size + gap
                }
                y += size + 30
            }
            rule(g, y, 1f); y += 18
        }
        return y
    }

    private fun drawRunningHead(g: Graphics2D, newspaper: Newspaper, pageNumber: Int): Int {
        var y = MARGIN
        g.font = fonts.blackletter.deriveFont(34f); g.color = INK
        g.drawString(config.title, MARGIN, y + 30)
        g.font = smallCaps; g.color = muted
        val right = "NO. ${newspaper.issueNumber} · ${dateLine(newspaper.toTime).uppercase()} · PAGE $pageNumber"
        g.drawString(right, PAGE_WIDTH - MARGIN - g.fontMetrics.stringWidth(right), y + 28)
        y += 42
        rule(g, y, 3f); y += 6; rule(g, y, 1f)
        return y + 22
    }

    private fun drawFolio(g: Graphics2D, newspaper: Newspaper, pageNumber: Int, height: Int) {
        g.font = smallCaps; g.color = muted
        centre(g, "— $pageNumber —", height - MARGIN + 6)
    }

    // ---- Content -----------------------------------------------------------

    /** A drop cap needs at least three lines to sit beside; short leads start plainly. */
    private fun leadBody(story: Story): List<Fragment> = paragraph(story.body, dropCap = story.body.length > 160)

    private fun sectionFragments(section: NewspaperSection, stories: List<Story>): List<Fragment> {
        val out = mutableListOf<Fragment>()
        out += sectionBand(section.title)
        if (section.title.equals("Statistics", true)) {
            stories.forEachIndexed { i, story -> out += statRow(story, keepWithNext = i < stories.lastIndex) }
            out += gap(20)
            return out
        }
        stories.forEachIndexed { index, story ->
            if (index > 0) out += storySeparator()
            val headSize = if (index == 0) 30f else 25f
            val headFont = fonts.bold.deriveFont(headSize)
            val hm = scratch.getFontMetrics(headFont)
            wrap(story.headline, hm, COLUMN_WIDTH).forEach { line ->
                out += Fragment(hm.ascent + hm.descent, keepWithNext = true) { g, x, y, w ->
                    g.font = headFont; g.color = INK
                    g.drawString(line, x + (w - g.fontMetrics.stringWidth(line)) / 2, y + hm.ascent)
                }
            }
            val face = story.players.firstNotNullOfOrNull { portraits[it] }
            out += if (face != null) {
                // Portrait beside the byline, naming who the story features.
                val featured = story.players.first { portraits[it] != null }
                Fragment(58, keepWithNext = true) { g, x, y, w ->
                    drawPortrait(g, face, x, y + 4, 48)
                    g.font = byline; g.color = muted
                    g.drawString(story.credit, x + 60, y + 24)
                    g.font = byline.deriveFont(java.awt.Font.BOLD)
                    g.drawString(featured, x + 60, y + 44)
                }
            } else {
                Fragment(24, keepWithNext = true) { g, x, y, w ->
                    g.font = byline; g.color = muted
                    val text = story.credit
                    g.drawString(text, x + (w - g.fontMetrics.stringWidth(text)) / 2, y + 17)
                }
            }
            val poll = story.poll
            out += if (poll != null && poll.total > 0) pollBars(poll) else paragraph(story.body, dropCap = false, keepLast = story.players.isNotEmpty())
            if (story.players.isNotEmpty()) {
                val names = story.players.take(6).joinToString(", ")
                wrap("— $names", scratch.getFontMetrics(bodyItalic), COLUMN_WIDTH).forEach { line ->
                    out += Fragment(BODY_LEADING - 2) { g, x, y, w ->
                        g.font = bodyItalic; g.color = muted
                        g.drawString(line, x + w - g.fontMetrics.stringWidth(line), y + 19)
                    }
                }
            }
        }
        out += gap(22)
        return out
    }

    private fun sectionBand(title: String): List<Fragment> = listOf(
        Fragment(44, keepWithNext = true) { g, x, y, w ->
            g.color = INK
            g.stroke = BasicStroke(2f); g.drawLine(x, y + 4, x + w, y + 4)
            g.color = accent
            // Letter-spaced small caps; tighten, then shrink, until the band fits the column.
            val upper = title.uppercase()
            val label = listOf(
                { upper.toCharArray().joinToString(" ") to 19f },
                { upper.toCharArray().joinToString("\u2009") to 19f },
                { upper to 19f },
                { upper to 16f },
                { upper to 13f },
            ).map { it() }.firstOrNull { (text, size) -> scratch.getFontMetrics(smallCaps.deriveFont(size)).stringWidth(text) <= w }
                ?: (upper to 12f)
            g.font = smallCaps.deriveFont(label.second)
            g.drawString(label.first, x + (w - g.fontMetrics.stringWidth(label.first)) / 2, y + 29)
            g.color = INK; g.stroke = BasicStroke(1f); g.drawLine(x, y + 38, x + w, y + 38)
        },
        gap(8, keep = true),
    )

    private fun statRow(story: Story, keepWithNext: Boolean): Fragment = Fragment(BODY_LEADING + 2, keepWithNext) { g, x, y, w ->
        g.font = body; g.color = INK
        val fm = g.fontMetrics
        val tokens = story.body.split(' ')
        // "48 km" keeps its unit; "6 unique player(s) this cycle" shows just the figure.
        val value = if (tokens.size <= 2) story.body else tokens.first()
        g.drawString(story.headline, x, y + 20)
        g.font = fonts.bold.deriveFont(BODY_SIZE)
        val vw = g.fontMetrics.stringWidth(value)
        g.drawString(value, x + w - vw, y + 20)
        g.color = muted
        var dot = x + fm.stringWidth(story.headline) + 8
        while (dot < x + w - vw - 10) { g.fillRect(dot, y + 18, 2, 2); dot += 7 }
    }

    /** Poll results as a small bar chart: label, bar in proportion to the leader, votes and share. */
    private fun pollBars(poll: PollResult): List<Fragment> {
        val max = poll.options.maxOf { it.votes }.coerceAtLeast(1)
        val rows = poll.options.mapIndexed { i, option ->
            Fragment(46, keepWithNext = true) { g, x, y, w ->
                g.font = fonts.bold.deriveFont(17f); g.color = INK
                g.drawString(option.label, x, y + 17)
                val share = option.votes * 100 / poll.total
                val figure = "${option.votes} · $share%"
                g.font = byline; g.color = muted
                val fw = g.fontMetrics.stringWidth(figure)
                g.drawString(figure, x + w - fw, y + 38)
                val barWidth = ((w - fw - 12) * option.votes / max).coerceAtLeast(2)
                g.color = if (option.votes == max) accent else muted
                g.fillRect(x, y + 25, barWidth, 14)
            }
        }
        return rows + Fragment(26) { g, x, y, w ->
            g.font = byline; g.color = muted
            val text = "${poll.total} reader${if (poll.total == 1) "" else "s"} voted"
            g.drawString(text, x + (w - g.fontMetrics.stringWidth(text)) / 2, y + 18)
        }
    }

    private fun storySeparator(): Fragment = Fragment(26, keepWithNext = true, spacer = true) { g, x, y, w ->
        g.color = INK; g.stroke = BasicStroke(1f)
        g.drawLine(x + w / 2 - 36, y + 12, x + w / 2 + 36, y + 12)
    }

    private fun gap(height: Int, keep: Boolean = false) = Fragment(height, keepWithNext = keep, spacer = true) { _, _, _, _ -> }

    /** Justified paragraph lines, optionally opening with a three-line drop cap. */
    private fun paragraph(text: String, dropCap: Boolean, keepLast: Boolean = false): List<Fragment> {
        val fm = scratch.getFontMetrics(body)
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return emptyList()
        val capLines = 3
        val capFont = fonts.bold.deriveFont(BODY_LEADING * capLines * 1.12f)
        val capMetrics = scratch.getFontMetrics(capFont)
        val cap = if (dropCap && clean.first().isLetter()) clean.substring(0, 1) else null
        val capWidth = cap?.let { capMetrics.stringWidth(it) + 8 } ?: 0
        val rest = if (cap != null) clean.substring(1) else clean

        val lines = mutableListOf<Pair<List<String>, Int>>() // words, indent
        val words = rest.split(' ').toMutableList()
        while (words.isNotEmpty()) {
            val indent = if (cap != null && lines.size < capLines) capWidth else 0
            val available = COLUMN_WIDTH - indent
            val line = mutableListOf(words.removeAt(0))
            while (words.isNotEmpty() && fm.stringWidth((line + words.first()).joinToString(" ")) <= available) {
                line += words.removeAt(0)
            }
            // Hyphenate the next word when the line is loose, to keep justified columns even.
            if (words.isNotEmpty()) {
                val slack = available - fm.stringWidth(line.joinToString(" "))
                if (slack > fm.stringWidth("  ") * 2) {
                    hyphenate(words.first(), available - fm.stringWidth(line.joinToString(" ") + " "), fm)?.let { (head, tail) ->
                        line += "$head-"
                        words[0] = tail
                    }
                }
            }
            lines += line to indent
        }
        return lines.mapIndexed { index, (lineWords, indent) ->
            val last = index == lines.lastIndex
            Fragment(BODY_LEADING, keepWithNext = cap != null && index < capLines - 1 || index == 0 && lines.size > 1 || keepLast && index == lines.lastIndex) { g, x, y, w ->
                g.font = body; g.color = INK
                val baseline = y + 20
                drawJustified(g, lineWords, x + indent, baseline, w - indent, justify = !last)
                if (index == 0 && cap != null) {
                    g.font = capFont
                    g.drawString(cap, x, y + BODY_LEADING * capLines - 6)
                }
            }
        }
    }

    /**
     * Splits [word] at the last plausible syllable boundary whose head (plus a
     * hyphen) fits in [space]: between a vowel and a following consonant+vowel,
     * or between doubled consonants, keeping at least three letters each side.
     */
    private fun hyphenate(word: String, space: Int, fm: FontMetrics): Pair<String, String>? {
        val letters = word.takeWhile { it.isLetter() }
        if (letters.length < 7 || space <= 0) return null
        val vowels = "aeiouyAEIOUY"
        var best: Int? = null
        for (i in 3..letters.length - 3) {
            val a = letters[i - 1]; val b = letters[i]; val c = letters.getOrNull(i + 1) ?: break
            val vcv = a in vowels && b !in vowels && c in vowels
            val doubled = a == b && a !in vowels
            if ((vcv || doubled) && fm.stringWidth(word.substring(0, i) + "-") <= space) best = i
        }
        return best?.let { word.substring(0, it) to word.substring(it) }
    }

    // ---- Flow --------------------------------------------------------------

    /**
     * Places fragments into the page's columns and returns what did not fit.
     * When everything fits, the columns are balanced to a common height.
     */
    private fun flow(g: Graphics2D, fragments: List<Fragment>, top: Int, bottom: Int, balanceLastPage: Boolean): List<Fragment> {
        val fullHeight = bottom - top
        var columnHeight = fullHeight
        var strict = false
        if (balanceLastPage && place(fragments, fullHeight, strict = true).second.isEmpty()) {
            // Shortest common column height that fits everything without splitting kept-together runs.
            var low = fragments.sumOf { it.height } / COLUMNS
            var high = fullHeight
            while (low < high) {
                val mid = (low + high) / 2
                if (place(fragments, mid, strict = true).second.isEmpty()) high = mid else low = mid + 1
            }
            columnHeight = low
            strict = true
        }
        lastColumnBottom = top + columnHeight
        val (placed, leftover) = place(fragments, columnHeight, strict)
        for ((fragment, pos) in placed) {
            val x = MARGIN + pos.first * (COLUMN_WIDTH + GUTTER)
            fragment.draw(g, x, top + pos.second, COLUMN_WIDTH)
        }
        // Column rules between used columns.
        val usedColumns = placed.maxOfOrNull { it.second.first } ?: 0
        g.color = INK; g.stroke = BasicStroke(1f)
        for (c in 1..usedColumns) {
            val x = MARGIN + c * (COLUMN_WIDTH + GUTTER) - GUTTER / 2
            g.drawLine(x, top, x, top + columnHeight)
        }
        return leftover
    }

    private fun place(fragments: List<Fragment>, columnHeight: Int, strict: Boolean = false): Pair<List<Pair<Fragment, Pair<Int, Int>>>, List<Fragment>> {
        val placed = mutableListOf<Pair<Fragment, Pair<Int, Int>>>()
        var column = 0
        var y = 0
        var i = 0
        while (i < fragments.size) {
            val fragment = fragments[i]
            if (fragment.spacer && y == 0) { i++; continue } // never open a column with space
            // Keep chained fragments together when they could fit in a fresh column.
            var group = fragment.height
            var j = i
            while (fragments[j].keepWithNext && j + 1 < fragments.size) { j++; group += fragments[j].height }
            if (strict && group > columnHeight) return placed to fragments.subList(i, fragments.size)
            val needed = if (group <= columnHeight) group else fragment.height
            if (y + needed > columnHeight && y > 0) {
                column++; y = 0
                if (column == COLUMNS) return placed to fragments.subList(i, fragments.size)
                continue
            }
            if (fragment.height > columnHeight) return placed to fragments.subList(i, fragments.size)
            placed += fragment to (column to y)
            y += fragment.height
            i++
        }
        return placed to emptyList()
    }

    /**
     * Draws an 8x8 skin face as a duotone newsprint photograph: luminance mapped
     * from ink to paper, blown up with hard pixel edges, in a thin ink frame.
     */
    private fun drawPortrait(g: Graphics2D, face: BufferedImage, x: Int, y: Int, size: Int) {
        val cell = size / 8
        for (py in 0 until 8) for (px in 0 until 8) {
            val argb = face.getRGB(px * face.width / 8, py * face.height / 8)
            val alpha = argb ushr 24
            val lum = if (alpha < 16) 1f else {
                val r = argb shr 16 and 0xFF; val gr = argb shr 8 and 0xFF; val b = argb and 0xFF
                (0.3f * r + 0.59f * gr + 0.11f * b) / 255f
            }
            val t = 0.08f + lum * 0.84f
            g.color = Color(
                (INK.red + (PAPER.red - INK.red) * t).toInt(),
                (INK.green + (PAPER.green - INK.green) * t).toInt(),
                (INK.blue + (PAPER.blue - INK.blue) * t).toInt(),
            )
            g.fillRect(x + px * cell, y + py * cell, cell, cell)
        }
        g.color = INK
        g.stroke = BasicStroke(1.5f)
        g.drawRect(x, y, cell * 8, cell * 8)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun blankPage(): BufferedImage {
        val image = BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = PAPER
        g.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT)
        g.dispose()
        return image
    }

    private fun drawJustified(g: Graphics2D, words: List<String>, x: Int, baseline: Int, width: Int, justify: Boolean) {
        val fm = g.fontMetrics
        if (!justify || words.size == 1) {
            g.drawString(words.joinToString(" "), x, baseline)
            return
        }
        val textWidth = words.sumOf { fm.stringWidth(it) }
        val gap = (width - textWidth).toFloat() / (words.size - 1)
        // A line this loose reads worse justified than ragged.
        if (gap > fm.stringWidth(" ") * 5f) {
            g.drawString(words.joinToString(" "), x, baseline)
            return
        }
        var cursor = x.toFloat()
        for (word in words) {
            g.drawString(word, cursor, baseline.toFloat())
            cursor += fm.stringWidth(word) + gap
        }
    }

    private fun wrap(text: String, fm: FontMetrics, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.trim().split(Regex("\\s+"))) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (fm.stringWidth(candidate) <= width || line.isEmpty()) line = candidate
            else { lines += line; line = word }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun centre(g: Graphics2D, text: String, baseline: Int) {
        g.drawString(text, (PAGE_WIDTH - g.fontMetrics.stringWidth(text)) / 2, baseline)
    }

    private fun rule(g: Graphics2D, y: Int, weight: Float) {
        g.color = INK
        g.stroke = BasicStroke(weight)
        g.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y)
    }

    private fun applyHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private fun dateLine(time: Long): String =
        config.formatDate(time, java.time.format.FormatStyle.FULL)
}

/** The bundled OFL typefaces; loaded from the plugin jar so no system fonts are needed. */
class NewspaperFonts(val blackletter: Font, val regular: Font, val bold: Font, val italic: Font) {
    companion object {
        private val bundled by lazy { load() }

        fun bundled(): NewspaperFonts = bundled

        fun load(): NewspaperFonts {
            fun font(name: String): Font =
                NewspaperFonts::class.java.getResourceAsStream("/fonts/$name")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
            return NewspaperFonts(
                blackletter = font("UnifrakturMaguntia-Book.ttf"),
                regular = font("OldStandard-Regular.ttf"),
                bold = font("OldStandard-Bold.ttf"),
                italic = font("OldStandard-Italic.ttf"),
            )
        }
    }
}
