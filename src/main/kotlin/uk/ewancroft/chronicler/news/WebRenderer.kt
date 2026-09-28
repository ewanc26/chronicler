package uk.ewancroft.chronicler.news

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import uk.ewancroft.chronicler.config.WebConfig
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

class WebRenderer(
    private val webConfig: WebConfig,
    private val newspaperConfig: uk.ewancroft.chronicler.config.NewspaperConfig,
    private val webDir: Path,
    private val archiveStore: ArchiveStore? = null,
    private val logger: Logger? = null,
) {

    /** Supplies Standard.site verification (publication URI and per-issue document links). */
    @Volatile
    var standardSite: uk.ewancroft.chronicler.publish.StandardSitePublisher? = null

    /** Extra binary routes (the resource pack); returns null when the path is not handled. */
    @Volatile
    var assetProvider: ((String) -> ByteArray?)? = null

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var latestHtml: String = "<html><body><h1>No newspaper published yet.</h1></body></html>"
    private var latestRss: String = ""
    private var latestNewspaper: Newspaper? = null

    fun renderAndServe(newspaper: Newspaper) {
        latestNewspaper = newspaper
        val html = renderHtml(newspaper)
        val rss = renderRss(newspaper)
        latestHtml = html
        latestRss = rss

        if (webConfig.writeFiles) {
            try {
                Files.createDirectories(webDir)
                webDir.resolve("index.html").toFile().writeText(html, StandardCharsets.UTF_8)
                webDir.resolve("rss.xml").toFile().writeText(rss, StandardCharsets.UTF_8)
                webDir.resolve("issue-${newspaper.issueNumber}.html").toFile().writeText(html, StandardCharsets.UTF_8)
            } catch (_: Exception) {
            }
        }

        if (server == null && webConfig.port > 0) {
            startServer()
        }
    }

    private fun renderHtml(newspaper: Newspaper): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
        val fromStr = dateFmt.format(Date(newspaper.fromTime))
        val toStr = dateFmt.format(Date(newspaper.toTime))
        val issueStr = "Issue #${newspaper.issueNumber} &mdash; $fromStr to $toStr"

        val sectionsHtml = newspaper.sections.joinToString("\n") { section ->
            val sectionSlug = slug(section.title)
            val storiesHtml = section.stories.mapIndexed { index, story ->
                val playersHtml = if (story.players.isNotEmpty()) {
                    "<p class=\"players\">— ${escapeHtml(story.players.joinToString(", "))}</p>"
                } else ""

                """
                <article class="story" id="$sectionSlug-$index">
                    <h3>${escapeHtml(story.headline)}</h3>
                    <p class="byline">By ${escapeHtml(story.byline)}</p>
                    <p>${escapeHtml(story.body)}</p>
                    $playersHtml
                </article>
                """
            }.joinToString("\n")

            val isStats = section.title == "Statistics"
            val inner = if (isStats) {
                """
                <h2>${escapeHtml(section.title)}</h2>
                <div class="stats-grid">
                    ${section.stories.joinToString("\n") { story ->
                        """
                        <div class="stat-card">
                            <div class="value">${escapeHtml(story.headline.split(" — ").lastOrNull() ?: story.headline)}</div>
                            <div class="label">${escapeHtml((story.headline.split(" — ").firstOrNull() ?: story.headline))}</div>
                        </div>
                        """
                    }}
                </div>
                """
            } else {
                """
                <h2>${escapeHtml(section.title)}</h2>
                $storiesHtml
                """
            }

            """
            <section id="$sectionSlug">
                $inner
            </section>
            """
        }

        val sectionNav = newspaper.sections.joinToString(" · ") { "<a href=\"#${slug(it.title)}\">${escapeHtml(it.title)}</a>" }
        val previous = archiveStore?.getIssue(newspaper.issueNumber - 1)?.let { "<a href=\"/issue/${it.issueNumber}\">← Issue #${it.issueNumber}</a>" } ?: ""
        val next = archiveStore?.getIssue(newspaper.issueNumber + 1)?.let { "<a href=\"/issue/${it.issueNumber}\">Issue #${it.issueNumber} →</a>" } ?: ""

        return """
<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${escapeHtml(newspaperConfig.title)} #${newspaper.issueNumber}</title>
    <meta http-equiv="refresh" content="300">
    <link rel="alternate" type="application/rss+xml" title="${escapeHtml(newspaperConfig.title)}" href="/rss.xml">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        :root {
            --bg: #1a1a2e;
            --bg-section: #16213e;
            --bg-card: #0f3460;
            --text: #e0e0e0;
            --text-secondary: #ccc;
            --text-muted: #888;
            --accent: #${newspaperConfig.accentColor.toString(16).padStart(6, '0')};
            --border: #333;
            --footer: #555;
        }
        [data-theme="light"] {
            --bg: #f5f1eb;
            --bg-section: #ffffff;
            --bg-card: #e8e0d0;
            --text: #1a1a1a;
            --text-secondary: #444;
            --text-muted: #777;
            --accent: #b8860b;
            --border: #ccc;
            --footer: #999;
        }
        body {
            font-family: 'Georgia', 'Times New Roman', serif;
            background: var(--bg);
            color: var(--text);
            line-height: 1.6;
            max-width: 800px;
            margin: 0 auto;
            padding: 2rem;
            transition: background 0.3s, color 0.3s;
        }
        .theme-toggle {
            position: fixed;
            top: 1rem;
            right: 1rem;
            background: var(--bg-card);
            color: var(--text);
            border: 1px solid var(--border);
            border-radius: 50%;
            width: 2.5rem;
            height: 2.5rem;
            cursor: pointer;
            font-size: 1.2rem;
            z-index: 100;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .theme-toggle:hover { opacity: 0.8; }
        nav { margin: 1rem 0; text-align: center; }
        nav a { color: var(--accent); }
        .issue-nav { display: flex; justify-content: space-between; }
        .search { text-align: center; margin: 1rem 0; }
        h1 {
            color: var(--accent);
            font-size: 2.5rem;
            text-align: center;
            border-bottom: 2px solid var(--accent);
            padding-bottom: 0.5rem;
            margin-bottom: 0.5rem;
        }
        .issue-info {
            text-align: center;
            color: var(--text-muted);
            margin-bottom: 2rem;
            font-size: 0.9rem;
        }
        section {
            margin-bottom: 2rem;
            padding: 1rem;
            background: var(--bg-section);
            border-radius: 8px;
        }
        h2 {
            color: var(--accent);
            font-size: 1.5rem;
            margin-bottom: 1rem;
            border-bottom: 1px solid var(--border);
            padding-bottom: 0.3rem;
        }
        .story {
            margin-bottom: 1rem;
            padding: 0.8rem;
            background: var(--bg-card);
            border-radius: 6px;
        }
        .story h3 {
            color: #ffffff;
            font-size: 1.1rem;
            margin-bottom: 0.3rem;
        }
        [data-theme="light"] .story h3 { color: var(--text); }
        .story p {
            color: var(--text-secondary);
            font-size: 0.95rem;
        }
        .story .byline {
            color: var(--text-muted);
            font-size: 0.8rem;
            font-style: italic;
            margin-bottom: 0.5rem;
        }
        .story .players {
            color: var(--text-muted);
            font-size: 0.8rem;
            margin-top: 0.3rem;
        }
        .footer {
            text-align: center;
            color: var(--footer);
            font-size: 0.8rem;
            margin-top: 3rem;
            padding-top: 1rem;
            border-top: 1px solid var(--border);
        }
        .stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
            gap: 0.5rem;
        }
        .stat-card {
            background: var(--bg-card);
            padding: 0.8rem;
            border-radius: 6px;
            text-align: center;
        }
        .stat-card .value {
            font-size: 1.5rem;
            color: var(--accent);
            font-weight: bold;
        }
        .stat-card .label {
            font-size: 0.85rem;
            color: var(--text-muted);
        }
    </style>
</head>
<body>
    <button class="theme-toggle" onclick="toggleTheme()" title="Toggle theme">◐</button>
    <h1>${escapeHtml(newspaperConfig.title)}</h1>
    <div class="issue-info">$issueStr</div>
    <form class="search" action="/search" method="get"><input name="q" aria-label="Search issues"><button type="submit">Search</button></form>
    <nav>$sectionNav</nav>
    <nav class="issue-nav"><span>$previous</span><a href="/archive">Issue archive</a><span>$next</span></nav>
    $sectionsHtml
    <div class="footer">
        Generated by Chronicler &mdash; ${toStr}
        &mdash; <a href="/rss.xml" style="color: var(--accent);">RSS Feed</a>
    </div>
    <script>
        function toggleTheme() {
            const html = document.documentElement;
            const current = html.getAttribute('data-theme');
            html.setAttribute('data-theme', current === 'dark' ? 'light' : 'dark');
            localStorage.setItem('chronicler-theme', html.getAttribute('data-theme'));
        }
        const saved = localStorage.getItem('chronicler-theme');
        if (saved) document.documentElement.setAttribute('data-theme', saved);
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun renderRss(newspaper: Newspaper): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
        val pubDate = dateFmt.format(Date(newspaper.toTime))
        val link = "http://localhost:${webConfig.port}/"

        val items = newspaper.sections.flatMap { section ->
            section.stories.map { story ->
                """
                <item>
                    <title>${escapeXml(story.headline)}</title>
                    <description>${escapeXml(story.body)}</description>
                    <link>$link</link>
                    <pubDate>$pubDate</pubDate>
                    <guid isPermaLink="false">chronicler-${newspaper.issueNumber}-${escapeXml(story.headline.take(40))}</guid>
                </item>
                """
            }
        }.joinToString("\n")

        return """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
<channel>
    <title>${escapeXml(newspaperConfig.title)}</title>
    <link>$link</link>
    <description>Chronicler newspaper for the Minecraft server</description>
    <language>en-us</language>
    <lastBuildDate>$pubDate</lastBuildDate>
    <atom:link href="${link}rss.xml" rel="self" type="application/rss+xml"/>
    $items
</channel>
</rss>"""
    }

    private fun startServer() {
        try {
            val addr = InetSocketAddress(webConfig.port)
            val pool = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
            )
            try {
                server = HttpServer.create(addr, 0).also { srv ->
                    srv.createContext("/", this::handleRequest)
                    srv.executor = pool
                    srv.start()
                }
                executor = pool
                logger?.info("Web edition serving on port ${webConfig.port}.")
            } catch (e: Exception) {
                pool.shutdownNow()
                throw e
            }
        } catch (e: Exception) {
            logger?.log(Level.WARNING, "Could not start web server on port ${webConfig.port}: ${e.message}")
        }
    }

    private fun handleRequest(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        assetProvider?.invoke(path)?.let { bytes ->
            exchange.responseHeaders.set("Content-Type", if (path.endsWith(".png")) "image/png" else "application/zip")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            return
        }
        if (path.startsWith("/print/") || path.startsWith("/chronicler-pack/")) {
            // Stale or unknown asset: a clear 404 rather than the HTML front page.
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        if (path == "/.well-known/site.standard.publication") {
            val uri = standardSite?.state?.publicationUri
            if (uri == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return }
            val bytes = uri.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            return
        }
        if (path == "/rss.xml" || path == "/rss") {
            handleRssRequest(exchange)
            return
        }
        val html = when {
            path == "/archive" -> renderArchiveIndex()
            path.startsWith("/issue/") -> path.removePrefix("/issue/").toIntOrNull()?.let { archiveStore?.getIssue(it) }?.let(::renderHtml)
                ?: "<html><body><h1>Issue not found</h1><a href=\"/archive\">Archive</a></body></html>"
            path == "/search" -> renderSearch(exchange.requestURI.rawQuery)
            else -> latestHtml
        }.let { page ->
            // Standard.site document verification: <link rel="site.standard.document" href="at://...">
            val number = if (path.startsWith("/issue/")) path.removePrefix("/issue/").toIntOrNull() else latestNewspaper?.issueNumber
            val uri = number?.let { standardSite?.documentUri(it) }
            if (uri == null) page else page.replaceFirst("</head>", "<link rel=\"site.standard.document\" href=\"${escapeHtml(uri)}\">\n</head>")
        }
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { body: OutputStream ->
            body.write(bytes)
        }
    }

    private fun renderArchiveIndex(): String {
        val issues = archiveStore?.getAll().orEmpty()
        val rows = issues.joinToString("\n") { issue ->
            val stories = issue.sections.sumOf { it.stories.size }
            "<li><a href=\"/issue/${issue.issueNumber}\">Issue #${issue.issueNumber}</a> — $stories stories</li>"
        }
        return "<html><head><meta charset=\"UTF-8\"><title>Issue Archive</title></head><body><h1>${escapeHtml(newspaperConfig.title)} Archive</h1><form action=\"/search\"><input name=\"q\"><button>Search</button></form><ul>$rows</ul><a href=\"/\">Latest issue</a></body></html>"
    }

    private fun renderSearch(rawQuery: String?): String {
        val query = rawQuery?.split('&')?.firstOrNull { it.startsWith("q=") }?.substringAfter("q=")
            ?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8) }?.trim().orEmpty()
        if (query.isBlank()) return renderArchiveIndex()
        val matches = archiveStore?.getAll().orEmpty().flatMap { issue ->
            issue.sections.flatMap { section -> section.stories.map { Triple(issue, section, it) } }
        }.filter { (_, section, story) ->
            section.title.contains(query, true) || story.headline.contains(query, true) || story.body.contains(query, true)
        }
        val rows = matches.joinToString("\n") { (issue, section, story) ->
            "<li><a href=\"/issue/${issue.issueNumber}#${slug(section.title)}\">${escapeHtml(story.headline)}</a> — Issue #${issue.issueNumber}</li>"
        }
        return "<html><head><meta charset=\"UTF-8\"><title>Search</title></head><body><h1>Search results for “${escapeHtml(query)}”</h1><ul>$rows</ul><a href=\"/archive\">Archive</a></body></html>"
    }

    private fun slug(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun handleRssRequest(exchange: HttpExchange) {
        val bytes = latestRss.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/rss+xml; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { body: OutputStream ->
            body.write(bytes)
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
