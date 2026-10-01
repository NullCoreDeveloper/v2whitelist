package com.kiktor.v2whitelist.util

import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.core.text.HtmlCompat
import java.util.regex.Pattern

object MarkdownUtil {

    private val BOLD_PATTERN_1 = Pattern.compile("\\*\\*(.+?)\\*\\*")
    private val BOLD_PATTERN_2 = Pattern.compile("__(.+?)__")
    private val ITALIC_PATTERN_1 = Pattern.compile("(?<!\\w)\\*([^*]+?)\\*(?!\\w)")
    private val ITALIC_PATTERN_2 = Pattern.compile("(?<!\\w)_([^_]+?)_(?!\\w)")
    private val INLINE_CODE_PATTERN = Pattern.compile("`([^`]+?)`")
    private val LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^\\)]+)\\)")
    private val RAW_URL_PATTERN = Pattern.compile("(?<!href=\")(?<!\">)(https?://[a-zA-Z0-9./?=_%&~#-]+)")

    /**
     * Конвертирует строку Markdown (например из GitHub Releases) в отформатированный Spanned.
     */
    fun toSpanned(markdown: String?): Spanned {
        if (markdown.isNullOrBlank()) {
            return HtmlCompat.fromHtml("", HtmlCompat.FROM_HTML_MODE_LEGACY)
        }

        val html = markdownToHtml(markdown)
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    /**
     * Устанавливает отформатированный Markdown в TextView с поддержкой кликабельных ссылок.
     */
    fun applyToTextView(textView: TextView, markdown: String?) {
        textView.text = toSpanned(markdown)
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.highlightColor = android.graphics.Color.TRANSPARENT
    }

    private fun markdownToHtml(md: String): String {
        val lines = md.replace("\r\n", "\n").split("\n")
        val htmlLines = StringBuilder()
        var inCodeBlock = false

        for (line in lines) {
            val trimmed = line.trim()

            // Блоки кода ``` ... ```
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }

            if (inCodeBlock) {
                htmlLines.append("<tt><font color=\"#888888\">")
                    .append(escapeHtml(line))
                    .append("</font></tt><br/>")
                continue
            }

            // Заголовки (#, ##, ###, ####)
            if (trimmed.startsWith("#")) {
                val headerLevel = trimmed.takeWhile { it == '#' }.length
                val title = trimmed.drop(headerLevel).trim()
                val escapedTitle = escapeHtml(title)
                when (headerLevel) {
                    1, 2 -> htmlLines.append("<br/><b><big><big>").append(escapedTitle).append("</big></big></b><br/>")
                    3 -> htmlLines.append("<br/><b><big>").append(escapedTitle).append("</big></b><br/>")
                    else -> htmlLines.append("<br/><b>").append(escapedTitle).append("</b><br/>")
                }
                continue
            }

            // Маркированные списки (- , * , • )
            val bulletMatch = Regex("""^(\s*)([-*•])\s+(.*)$""").find(line)
            if (bulletMatch != null) {
                val (indent, _, content) = bulletMatch.destructured
                val depth = indent.length / 2
                val spaces = "&nbsp;&nbsp;".repeat(depth)
                htmlLines.append(spaces)
                    .append("• ")
                    .append(processInlineMarkdown(escapeHtml(content)))
                    .append("<br/>")
                continue
            }

            // Нумерованные списки (1. , 2. )
            val numMatch = Regex("""^(\s*)(\d+\.)\s+(.*)$""").find(line)
            if (numMatch != null) {
                val (indent, num, content) = numMatch.destructured
                val depth = indent.length / 2
                val spaces = "&nbsp;&nbsp;".repeat(depth)
                htmlLines.append(spaces)
                    .append(num)
                    .append(" ")
                    .append(processInlineMarkdown(escapeHtml(content)))
                    .append("<br/>")
                continue
            }

            // Цитаты (> ...)
            if (trimmed.startsWith(">")) {
                val quote = trimmed.drop(1).trim()
                htmlLines.append("<font color=\"#888888\"><i>&gt; ")
                    .append(processInlineMarkdown(escapeHtml(quote)))
                    .append("</i></font><br/>")
                continue
            }

            // Пустая строка
            if (trimmed.isEmpty()) {
                htmlLines.append("<br/>")
                continue
            }

            // Обычная строка
            htmlLines.append(processInlineMarkdown(escapeHtml(line))).append("<br/>")
        }

        // Удаляем избыточные пустые строки в начале и конце
        var result = htmlLines.toString()
            .replace(Regex("""^(<br/>)+"""), "")
            .replace(Regex("""(<br/>){3,}"""), "<br/><br/>")

        return result
    }

    private fun processInlineMarkdown(escaped: String): String {
        var res = escaped

        // Ссылки: [title](url)
        val linkMatcher = LINK_PATTERN.matcher(res)
        val linkSb = StringBuffer()
        while (linkMatcher.find()) {
            val title = linkMatcher.group(1) ?: ""
            val url = linkMatcher.group(2) ?: ""
            linkMatcher.appendReplacement(linkSb, "<a href=\"$url\">$title</a>")
        }
        linkMatcher.appendTail(linkSb)
        res = linkSb.toString()

        // Прямые URL (если не внутри уже созданных <a>)
        val rawUrlMatcher = RAW_URL_PATTERN.matcher(res)
        val rawUrlSb = StringBuffer()
        while (rawUrlMatcher.find()) {
            val url = rawUrlMatcher.group(1) ?: ""
            rawUrlMatcher.appendReplacement(rawUrlSb, "<a href=\"$url\">$url</a>")
        }
        rawUrlMatcher.appendTail(rawUrlSb)
        res = rawUrlSb.toString()

        // Жирный шрифт: **text** или __text__
        res = BOLD_PATTERN_1.matcher(res).replaceAll("<b>$1</b>")
        res = BOLD_PATTERN_2.matcher(res).replaceAll("<b>$1</b>")

        // Курсив: *text* или _text_
        res = ITALIC_PATTERN_1.matcher(res).replaceAll("<i>$1</i>")
        res = ITALIC_PATTERN_2.matcher(res).replaceAll("<i>$1</i>")

        // Инлайн код: `code`
        res = INLINE_CODE_PATTERN.matcher(res).replaceAll("<tt><font color=\"#666666\">$1</font></tt>")

        return res
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
