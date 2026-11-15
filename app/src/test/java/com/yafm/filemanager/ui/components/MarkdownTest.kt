package com.yafm.filemanager.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun blocks() {
        val md = """
            # Title
            Some *text*
            continued.

            - one
              - nested
            1. first
            - [x] done

            > quote

            ```
            code
            ```
            | a | b |
            |---|---|
            | 1 | 2 |
            ---
        """.trimIndent()
        val b = Markdown.parse(md)
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Paragraph("Some *text* continued."), b[1])
        assertEquals(MdBlock.ListItem(0, "•", "one"), b[2])
        assertEquals(MdBlock.ListItem(1, "•", "nested"), b[3])
        assertEquals(MdBlock.ListItem(0, "1.", "first"), b[4])
        assertEquals(MdBlock.ListItem(0, "☑", "done"), b[5])
        assertEquals(MdBlock.Quote("quote"), b[6])
        assertEquals(MdBlock.Code("code"), b[7])
        assertEquals(MdBlock.Table(listOf(listOf("a", "b"), listOf("1", "2"))), b[8])
        assertTrue(b[9] is MdBlock.Rule)
    }

    @Test fun setextHeading() {
        assertEquals(listOf(MdBlock.Heading(2, "Sub")), Markdown.parse("Sub\n---"))
    }
}

class MarkdownInlineTest {
    @Test fun boldItalicStyles() {
        val s = Markdown.inline("Some **bold**, *italic*, `code`.", androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Blue)
        assertEquals("Some bold, italic, code.", s.text)
        val bold = s.spanStyles.first { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold }
        assertEquals("bold", s.text.substring(bold.start, bold.end))
        val italic = s.spanStyles.first { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic }
        assertEquals("italic", s.text.substring(italic.start, italic.end))
    }
}
