package com.coderarchitech.jobassistant.sources.normalize

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

/** Plain text from posting HTML, kept readable for the scoring prompt and the Job detail screen. */
object HtmlText {
    /** The most description text sent in a prompt (design, Normalization). */
    const val PROMPT_CAP = 8_000

    private val BLOCKS =
        setOf(
            "p",
            "div",
            "br",
            "li",
            "ul",
            "ol",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "tr",
            "table",
            "section",
            "article",
            "header",
            "footer",
            "blockquote",
            "pre",
            "hr",
        )

    /** Table and definition cells, kept apart by a space so `<td>Salary</td><td>$1</td>` reads `Salary $1`. */
    private val CELLS = setOf("td", "th", "dt", "dd")
    private val OWN_LINE = setOf("li", "tr")
    private val SPACES = Regex("[ \\t\\u00A0\\u200B]+")
    private val BLANK_LINES = Regex("\\n{3,}")

    /**
     * The text of [html] with paragraphs and list items on their own lines (items start with `- `), entities
     * decoded, scripts and styles dropped and runs of spaces collapsed. Text without tags passes through.
     */
    fun toPlainText(html: String): String {
        val body = Jsoup.parse(html).body()
        body.select("script, style, noscript, template").remove()
        val out = StringBuilder()
        body.traverse(
            object : NodeVisitor {
                override fun head(
                    node: Node,
                    depth: Int,
                ) {
                    when {
                        node is TextNode -> out.append(node.wholeText)
                        node is Element && node.normalName() == "li" -> out.append("\n- ")
                        node is Element && node.normalName() in CELLS -> out.append(' ')
                        node is Element && node.normalName() in BLOCKS -> out.append('\n')
                    }
                }

                override fun tail(
                    node: Node,
                    depth: Int,
                ) {
                    // A list item or table row already starts on its own line, so it adds no break after itself.
                    val name = (node as? Element)?.normalName()
                    if (name in BLOCKS && name !in OWN_LINE) out.append('\n')
                }
            },
        )
        return out
            .lines()
            .joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(BLANK_LINES, "\n\n")
            .trim()
    }

    /**
     * [text] cut to at most [max] characters, at the last line or word break before the limit when there
     * is one, with `…` marking the cut. Text within the limit is returned as it is.
     */
    fun cap(
        text: String,
        max: Int = PROMPT_CAP,
    ): String {
        if (text.length <= max) return text
        val room = text.substring(0, max - 1)
        val breakAt = maxOf(room.lastIndexOf('\n'), room.lastIndexOf(' '))
        val cut = if (breakAt > max / 2) room.substring(0, breakAt) else room
        return cut.trimEnd() + "…"
    }
}
