package com.yafm.filemanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File

/** Blocks of a Markdown text, parsed once: what the preview draws. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class ListItem(val depth: Int, val marker: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Table(val rows: List<List<String>>) : MdBlock()
    data class Image(val alt: String, val src: String) : MdBlock()
    data object Rule : MdBlock()
}

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val image = Regex("^!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flush()
                    val fence = trimmed.take(3)
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    out += MdBlock.Code(code.toString())
                }
                line.isBlank() -> flush()
                rule.matches(line) && para.isEmpty() -> out += MdBlock.Rule
                heading.matches(trimmed) -> {
                    flush()
                    val m = heading.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                // Setext headings: a line under "===" or "---".
                para.isNotEmpty() && Regex("^=+\\s*$").matches(line) -> {
                    out += MdBlock.Heading(1, para.toString().trim()); para.clear()
                }
                para.isNotEmpty() && Regex("^-+\\s*$").matches(line) -> {
                    out += MdBlock.Heading(2, para.toString().trim()); para.clear()
                }
                image.matches(trimmed) -> {
                    flush()
                    val m = image.find(trimmed)!!
                    out += MdBlock.Image(m.groupValues[1], m.groupValues[2])
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        if (quote.isNotEmpty()) quote.append('\n')
                        quote.append(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                        i++
                    }
                    out += MdBlock.Quote(quote.toString())
                    continue
                }
                bullet.matches(line) || ordered.matches(line) -> {
                    flush()
                    val b = bullet.find(line)
                    val (indent, marker, body) = if (b != null) {
                        Triple(b.groupValues[1], "•", b.groupValues[3])
                    } else {
                        val o = ordered.find(line)!!
                        Triple(o.groupValues[1], o.groupValues[2] + ".", o.groupValues[3])
                    }
                    val t = task.find(body)
                    val depth = indent.replace("\t", "    ").length / 2
                    out += if (t != null) MdBlock.ListItem(depth, if (t.groupValues[1] == " ") "☐" else "☑", t.groupValues[2])
                    else MdBlock.ListItem(depth, marker, body)
                }
                '|' in line && i + 1 < lines.size && tableDivider.matches(lines[i + 1]) -> {
                    flush()
                    val rows = mutableListOf(cells(line))
                    i += 2
                    while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) {
                        rows += cells(lines[i]); i++
                    }
                    out += MdBlock.Table(rows)
                    continue
                }
                // Four spaces: a code block, unless it continues a paragraph.
                para.isEmpty() && (line.startsWith("    ") || line.startsWith("\t")) -> {
                    val code = StringBuilder()
                    while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i].removePrefix("    ").removePrefix("\t"))
                        i++
                    }
                    out += MdBlock.Code(code.toString().trimEnd())
                    continue
                }
                else -> {
                    if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " ")
                    para.append(line.trim())
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** **bold**, *italic*, ~~struck~~, `code`, [links](url) and <autolinks>. */
    fun inline(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        val links = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        fun rest(from: Int, marker: String): Int = text.indexOf(marker, from).takeIf { it > from } ?: -1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> { append(text[i + 1]); i += 2 }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) { append(c); i++ } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val end = rest(i + 2, text.substring(i, i + 2))
                    if (end < 0) { append(text.substring(i, i + 2)); i += 2 } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), codeBackground, linkColor)) }
                        i = end + 2
                    }
                }
                text.startsWith("~~", i) -> {
                    val end = rest(i + 2, "~~")
                    if (end < 0) { append("~~"); i += 2 } else {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inline(text.substring(i + 2, end), codeBackground, linkColor)) }
                        i = end + 2
                    }
                }
                (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                    val end = rest(i + 1, c.toString())
                    // snake_case words are not italic.
                    if (end < 0 || (c == '_' && i > 0 && text[i - 1].isLetterOrDigit())) { append(c); i++ } else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, end), codeBackground, linkColor)) }
                        i = end + 1
                    }
                }
                c == '[' -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > 0) text.indexOf(')', close) else -1
                    if (close < 0 || end < 0) { append(c); i++ } else {
                        val url = text.substring(close + 2, end).substringBefore(' ')
                        withLink(LinkAnnotation.Url(url, links)) { append(inline(text.substring(i + 1, close), codeBackground, linkColor)) }
                        i = end + 1
                    }
                }
                c == '<' && (text.startsWith("<http://", i) || text.startsWith("<https://", i)) -> {
                    val end = text.indexOf('>', i)
                    if (end < 0) { append(c); i++ } else {
                        val url = text.substring(i + 1, end)
                        withLink(LinkAnnotation.Url(url, links)) { append(url) }
                        i = end + 1
                    }
                }
                else -> { append(c); i++ }
            }
        }
    }
}

/** A rendered Markdown text; [baseDir] resolves relative image paths of a local file. */
@Composable
fun MarkdownView(text: String, baseDir: File?, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    val scheme = MaterialTheme.colorScheme
    val codeBg = scheme.surfaceVariant
    val link = scheme.primary
    SelectionContainer(modifier) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(blocks) { block ->
                when (block) {
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineMedium
                            2 -> MaterialTheme.typography.headlineSmall
                            3 -> MaterialTheme.typography.titleLarge
                            4 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        Column {
                            Spacer(Modifier.height(if (block.level <= 2) 8.dp else 4.dp))
                            Text(Markdown.inline(block.text, codeBg, link), style = style, fontWeight = FontWeight.Bold)
                            if (block.level <= 2) HorizontalDivider(Modifier.padding(top = 4.dp))
                        }
                    }
                    is MdBlock.Paragraph -> Text(Markdown.inline(block.text, codeBg, link), style = MaterialTheme.typography.bodyLarge)
                    is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                        Text(block.marker, modifier = Modifier.widthIn(min = 20.dp), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(4.dp))
                        Text(Markdown.inline(block.text, codeBg, link), style = MaterialTheme.typography.bodyLarge)
                    }
                    is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(scheme.primary.copy(alpha = 0.5f)))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            Markdown.inline(block.text, codeBg, link),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    is MdBlock.Code -> Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(codeBg)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                    is MdBlock.Table -> Column(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(4.dp))
                    ) {
                        val columns = block.rows.maxOf { it.size }
                        block.rows.forEachIndexed { r, row ->
                            Row(Modifier.background(if (r == 0) codeBg else Color.Transparent)) {
                                (0 until columns).forEach { c ->
                                    Text(
                                        Markdown.inline(row.getOrElse(c) { "" }, codeBg, link),
                                        fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.widthIn(min = 80.dp, max = 240.dp).padding(8.dp)
                                    )
                                }
                            }
                            if (r < block.rows.size - 1) HorizontalDivider(color = scheme.outlineVariant)
                        }
                    }
                    is MdBlock.Image -> {
                        val local = baseDir?.let { dir ->
                            block.src.takeIf { !it.contains("://") }?.let { File(dir, it.removePrefix("./")) }?.takeIf { it.isFile }
                        }
                        if (local != null) {
                            AsyncImage(
                                model = local,
                                contentDescription = block.alt,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Text(
                                Markdown.inline("[🖼 ${block.alt.ifEmpty { block.src }}](${block.src})", codeBg, link),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}
