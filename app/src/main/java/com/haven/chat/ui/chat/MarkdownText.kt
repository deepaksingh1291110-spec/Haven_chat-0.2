/*
 * ============================================================================
 *  MarkdownText.kt  -  a tiny Markdown renderer for Jetpack Compose
 * ============================================================================
 *
 *  WHAT
 *  ----
 *  One public Composable function: MarkdownText(text, ...).
 *  It takes a Markdown string and draws it with Compose (headings, bold,
 *  italic, lists, code blocks, quotes, links, ...).
 *
 *  WHY
 *  ---
 *  The AI model (Gemma) answers in Markdown, and it answers by STREAMING:
 *  the text grows a few characters at a time, and this function is called
 *  again and again. So the parser must work on INCOMPLETE Markdown:
 *      "**bold te"   -> shown exactly as "**bold te" (nothing disappears)
 *      "```kotlin\nval a = 1"  -> shown as a code block even without the
 *                                 closing fence yet
 *  We use NO external library. Everything is plain Kotlin written by hand,
 *  so it is easy to read and to change.
 *
 *  HOW  (two steps, like a small compiler)
 *  ---
 *  Step 1  BLOCK parser   parseBlocks(text)
 *          Reads the text LINE BY LINE and decides what each line is:
 *          heading, bullet, numbered item, code fence, rule, quote or
 *          normal paragraph text. Result: a List<Block>.
 *
 *  Step 2  INLINE parser  parseInline(text, colors)
 *          Looks INSIDE one block's text for **bold**, *italic*, `code`,
 *          ~~strike~~, [links](url) and \escapes. Result: an
 *          AnnotatedString (text + styles) that Compose's Text() can draw.
 *
 *  Step 3  Small composables draw each Block type.
 *
 *  EXTRA FEATURES
 *  --------------
 *  - TABLES (GitHub style):  | a | b |  then  |---|---|  then rows.
 *    Drawn as a grid that scrolls sideways if it is too wide.
 *  - A SMALL set of HTML tags that AI models often write:
 *        <br>  <b> <strong>  <i> <em>  <u>  <s> <del>
 *        <span style="color:blue">..</span>   <font color="pink">..</font>
 *    All other HTML stays plain text (no WebView, no full HTML).
 *  - TEXT COLORS through the color tags above. Names (red, blue, pink...)
 *    use soft shades that look good on a dark theme. Hex works too: #FF69B4.
 *  - Images and footnotes are NOT supported: they stay plain text.
 *
 *  MATH (LaTeX) SUPPORT (added after Phase 3)
 *  ------------------------------------------
 *  Gemma writes math in LaTeX: $x^2$, $$\frac{a}{b}$$, \(..\), \[..\].
 *  Two hooks turn it into readable text; the converter itself is in
 *  MathText.kt (latexToAnnotated, findInlineMath, detectMathBlock):
 *    - BLOCK parser:  a formula that fills whole lines ($$ ... $$ or
 *      \[ ... \]) becomes Block.Math and is drawn centered by MathBlockView.
 *    - INLINE parser: a formula inside a sentence is converted in place by
 *      handleDollar (for $ and $$) and handleBackslash (for \( and \[).
 *  Prices like "$5 and $10" stay normal text. A formula whose closing sign
 *  has not arrived yet (streaming) stays plain text until it arrives.
 *  "\$" shows a normal dollar sign.
 *
 *  COPY BUTTON ON CODE BLOCKS (added after Phase 3)
 *  ------------------------------------------------
 *  Every code block has a small "Copy" button in its top row (next to the
 *  language name). One tap puts ONLY the code on the clipboard and the
 *  button says "Copied" for 1.5 seconds. The top row is wrapped in
 *  DisableSelection, so when the user selects text by long-press, the
 *  words "python" and "Copy" are never part of the selection.
 *  (Text selection itself is switched on in MessageBubble.kt with a
 *  SelectionContainer around MarkdownText.)
 *
 *  SAFETY RULES
 *  ------------
 *  - Parsing is wrapped in try/catch. Worst case = plain text, never a crash.
 *  - Parsing is (roughly) linear: no regex, no exponential backtracking.
 *  - parseBlocks() runs inside remember(text), so it only runs again when the
 *    text really changed.
 *
 *  For a C/C++ beginner: a Kotlin "sealed interface" is like a C "tagged
 *  union" (a struct + an enum). "when (x) { is A -> ... }" is like a switch.
 * ============================================================================
 */
package com.haven.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay

// ============================================================================
//  1. PUBLIC API  (the only public thing in this file)
// ============================================================================

/**
 * Draws [text] as Markdown. Safe to call with incomplete (streaming) text.
 *
 * @param text   the Markdown text (may be incomplete, may be empty)
 * @param color  color of normal text
 * @param style  base text style for paragraphs, list items and quotes
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    // Read the theme once. These are cheap lookups.
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography

    // "Look" = every color and text style we need, collected in one object,
    // so we can pass ONE thing to the small drawing functions below.
    val look = Look(
        color = color,
        body = style,
        heading1 = type.titleLarge.copy(fontWeight = FontWeight.Bold),
        heading2 = type.titleMedium.copy(fontWeight = FontWeight.Bold),
        heading3 = type.titleSmall.copy(fontWeight = FontWeight.Bold),
        codeStyle = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        labelStyle = type.labelSmall,
        codeText = scheme.onSurface,
        // Code block background: surfaceVariant made a little darker, so it
        // is still visible when the chat bubble itself is surfaceVariant.
        codeBackground = lerp(scheme.surfaceVariant, Color.Black, 0.3f),
        codeLabel = scheme.onSurfaceVariant.copy(alpha = 0.7f),
        quoteBar = scheme.primary.copy(alpha = 0.6f),
        quoteText = color.copy(alpha = 0.75f),
        rule = scheme.outlineVariant,
        tableBody = type.bodyMedium,
        tableHeader = type.bodyMedium.copy(fontWeight = FontWeight.Bold),
        tableHeaderBackground = scheme.surfaceVariant,
        inline = InlineColors(
            codeBackground = scheme.surfaceVariant,
            codeText = scheme.onSurfaceVariant,
            link = scheme.primary
        )
    )

    // Parse ONLY when the text changes (not on every recomposition).
    val blocks = remember(text) { parseBlocks(text) }

    // Root column. If there are no blocks (empty text) nothing is drawn.
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // We walk through the blocks by hand because we want to put
        // consecutive list items into ONE group with 6 dp spacing
        // (the root column uses 8 dp between normal blocks).
        var index = 0
        while (index < blocks.size) {
            val block = blocks[index]
            if (isListItem(block)) {
                // Find where this run of list items ends.
                var end = index
                while (end < blocks.size && isListItem(blocks[end])) end++
                ListGroup(items = blocks.subList(index, end), look = look)
                index = end
            } else {
                BlockView(block = block, look = look)
                index++
            }
        }
    }
}

// ============================================================================
//  2. SMALL DATA MODEL
// ============================================================================

/** Everything the drawing functions need to know about colors and styles. */
private data class Look(
    val color: Color,
    val body: TextStyle,
    val heading1: TextStyle,
    val heading2: TextStyle,
    val heading3: TextStyle,
    val codeStyle: TextStyle,
    val labelStyle: TextStyle,
    val codeText: Color,
    val codeBackground: Color,
    val codeLabel: Color,
    val quoteBar: Color,
    val quoteText: Color,
    val rule: Color,
    val tableBody: TextStyle,
    val tableHeader: TextStyle,
    val tableHeaderBackground: Color,
    val inline: InlineColors
)

/** The colors the INLINE parser needs (inline code and links). */
private data class InlineColors(
    val codeBackground: Color,
    val codeText: Color,
    val link: Color
)

/**
 * One "block" of the document. Each case holds the raw text that still
 * has to go through the inline parser (bold, italic, ...), except Code,
 * which is shown exactly as written.
 */
private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    /** level = 0..3 (nesting depth). */
    data class Bullet(val level: Int, val text: String) : Block
    /** number = what the model wrote, with its delimiter, e.g. "1." or "2)". */
    data class Numbered(val level: Int, val number: String, val text: String) : Block
    data class Code(val language: String, val code: String) : Block
    object Rule : Block
    data class Quote(val text: String) : Block
    /** MATH: a display formula on its own lines ($$ ... $$ or \[ ... \]). Raw LaTeX. */
    data class Math(val latex: String) : Block
    /**
     * A table. aligns has one entry per column: 0 = left, 1 = center, 2 = right.
     * Every row has exactly header.size cells (padded or cut by the parser).
     */
    data class Table(
        val header: List<String>,
        val aligns: List<Int>,
        val rows: List<List<String>>
    ) : Block
}

private fun isListItem(block: Block): Boolean =
    block is Block.Bullet || block is Block.Numbered

// ============================================================================
//  3. BLOCK PARSER  (line by line)
// ============================================================================

/**
 * Turns the whole text into a list of blocks. Never throws:
 * if anything goes wrong, the whole text becomes one plain paragraph.
 */
private fun parseBlocks(raw: String): List<Block> {
    return try {
        parseBlocksUnsafe(raw)
    } catch (e: Exception) {
        if (raw.isBlank()) emptyList() else listOf(Block.Paragraph(raw))
    }
}

private fun parseBlocksUnsafe(raw: String): List<Block> {
    // Windows line endings: "\r\n" (and old Mac "\r") become "\n".
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
    if (text.isBlank()) return emptyList()

    val lines = text.split('\n')
    val blocks = ArrayList<Block>()

    // Normal text lines are collected here until the paragraph ends.
    val paragraph = ArrayList<String>()

    // True right after a list item, so an indented line can continue it.
    var canContinueItem = false

    // Local helper: turn the collected lines into a Paragraph block.
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(Block.Paragraph(paragraph.joinToString("\n")))
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // ---- Blank line: ends the paragraph. Many blank lines = one gap
        //      (we simply add nothing, so the gap can not grow).
        if (trimmed.isEmpty()) {
            flushParagraph()
            canContinueItem = false
            i++
            continue
        }

        // ---- Fenced code block:  ```lang  ...  ```   (or ~~~)
        val fence = detectFence(trimmed)
        if (fence != null) {
            flushParagraph()
            canContinueItem = false
            val indent = line.length - line.trimStart().length
            val codeLines = ArrayList<String>()
            i++
            // Collect lines until the closing fence. If the closing fence has
            // not arrived yet (streaming), we simply run to the end of the text.
            while (i < lines.size) {
                if (isClosingFence(lines[i].trim(), fence)) break
                codeLines.add(dropIndent(lines[i], indent))
                i++
            }
            i++ // skip the closing fence line (or step past the end)
            blocks.add(Block.Code(fence.language, codeLines.joinToString("\n").trimEnd('\n')))
            continue
        }

        // ---- MATH: a display formula that fills whole lines:
        //      $$ ... $$   or   \[ ... \]   (one line or several lines).
        //      If the closing sign has not arrived yet (streaming), or text
        //      follows it on the same line, this returns null and the text
        //      is handled as a normal paragraph (inline math still works there).
        val mathBlock = detectMathBlock(lines, i)
        if (mathBlock != null) {
            flushParagraph()
            canContinueItem = false
            blocks.add(Block.Math(mathBlock.latex))
            i = mathBlock.nextIndex
            continue
        }

        // ---- Heading:  "# " .. "###### "  (the space is REQUIRED)
        val headingSource = line.trimStart()
        if (headingSource.startsWith("#")) {
            var hashes = 0
            while (hashes < headingSource.length && headingSource[hashes] == '#') hashes++
            val nextChar = if (hashes < headingSource.length) headingSource[hashes] else 'x'
            if (hashes <= 6 && (nextChar == ' ' || nextChar == '\t')) {
                flushParagraph()
                canContinueItem = false
                blocks.add(Block.Heading(hashes, headingSource.substring(hashes).trim()))
                i++
                continue
            }
            // Otherwise it is something like "#hashtag": plain text (below).
        }

        // ---- Horizontal rule: only ---, *** or ___ (3 or more, same char)
        if (isRuleLine(trimmed)) {
            flushParagraph()
            canContinueItem = false
            blocks.add(Block.Rule)
            i++
            continue
        }

        // ---- Block quote: consecutive lines that start with ">"
        if (trimmed.startsWith(">")) {
            flushParagraph()
            canContinueItem = false
            val quoteLines = ArrayList<String>()
            while (i < lines.size) {
                val q = lines[i].trim()
                if (!q.startsWith(">")) break
                quoteLines.add(q.substring(1).trim())
                i++
            }
            blocks.add(Block.Quote(quoteLines.joinToString("\n").trim()))
            continue
        }

        // ---- Lists. Level = indentation / 2 (a tab counts as 2), max level 3.
        val width = indentWidth(line)
        val level = minOf(width / 2, 3)
        val body = line.trimStart()

        val bulletText = detectBullet(body)
        if (bulletText != null) {
            flushParagraph()
            blocks.add(Block.Bullet(level, bulletText))
            canContinueItem = true
            i++
            continue
        }

        val numbered = detectNumbered(body)
        if (numbered != null) {
            flushParagraph()
            blocks.add(Block.Numbered(level, numbered.number, numbered.text))
            canContinueItem = true
            i++
            continue
        }

        // ---- Table: a header row, then a delimiter row like |---|:---:|
        //      (the delimiter row is what proves it is a table, so a single
        //      line with "|" characters stays plain text).
        if (trimmed.contains('|') && i + 1 < lines.size) {
            val delimiter = lines[i + 1].trim()
            if (isDelimiterRow(delimiter)) {
                val header = splitRow(trimmed)
                if (header.size in 1..30) {
                    flushParagraph()
                    canContinueItem = false
                    val columns = header.size
                    val aligns = parseAligns(delimiter, columns)
                    i += 2
                    val rows = ArrayList<List<String>>()
                    // Body rows: every following line that contains "|".
                    while (i < lines.size) {
                        val rowLine = lines[i].trim()
                        if (rowLine.isEmpty() || !rowLine.contains('|')) break
                        rows.add(fitCells(splitRow(rowLine), columns))
                        i++
                    }
                    blocks.add(Block.Table(header, aligns, rows))
                    continue
                }
            }
        }

        // ---- An indented line right after a list item continues that item.
        if (canContinueItem && paragraph.isEmpty() && width >= 2 && blocks.isNotEmpty()) {
            val last = blocks[blocks.size - 1]
            if (last is Block.Bullet) {
                blocks[blocks.size - 1] = last.copy(text = joinLines(last.text, trimmed))
                i++
                continue
            }
            if (last is Block.Numbered) {
                blocks[blocks.size - 1] = last.copy(text = joinLines(last.text, trimmed))
                i++
                continue
            }
        }

        // ---- Anything else is normal paragraph text. This also covers
        //      tables, images, HTML and footnotes: they stay plain text.
        paragraph.add(trimmed)
        canContinueItem = false
        i++
    }

    flushParagraph()
    return blocks
}

// ---- small helpers for the block parser -----------------------------------

/** Information about an opening code fence. */
private class Fence(val char: Char, val length: Int, val language: String)

/**
 * Is this (trimmed) line an opening fence? ``` or ~~~ (3 or more of the same
 * character) with an optional language name after it.
 * Two backticks only ("``") are NOT a fence: they stay plain text.
 */
private fun detectFence(trimmed: String): Fence? {
    if (trimmed.length < 3) return null
    val c = trimmed[0]
    if (c != '`' && c != '~') return null
    var count = 0
    while (count < trimmed.length && trimmed[count] == c) count++
    if (count < 3) return null
    val info = trimmed.substring(count).trim()
    // "```code``` text" on one line is inline code, not a fence.
    if (c == '`' && info.contains('`')) return null
    val language = info.takeWhile { !it.isWhitespace() }
    return Fence(c, count, language)
}

/** A closing fence has only the fence character, at least as many as the opening one. */
private fun isClosingFence(trimmed: String, fence: Fence): Boolean {
    if (trimmed.length < fence.length) return false
    for (ch in trimmed) {
        if (ch != fence.char) return false
    }
    return true
}

/** Remove up to [indent] leading spaces/tabs (used for fences inside lists). */
private fun dropIndent(line: String, indent: Int): String {
    var k = 0
    while (k < indent && k < line.length && (line[k] == ' ' || line[k] == '\t')) k++
    return line.substring(k)
}

/** "---", "***" or "___": 3 or more of the same character and nothing else. */
private fun isRuleLine(trimmed: String): Boolean {
    if (trimmed.length < 3) return false
    val c = trimmed[0]
    if (c != '-' && c != '*' && c != '_') return false
    for (ch in trimmed) {
        if (ch != c) return false
    }
    return true
}

/** How many columns of indentation a line has. A space = 1, a tab = 2. */
private fun indentWidth(line: String): Int {
    var width = 0
    for (ch in line) {
        if (ch == ' ') width++
        else if (ch == '\t') width += 2
        else break
    }
    return width
}

/** If [body] (already left-trimmed) starts with "- ", "* " or "+ ", return the item text. */
private fun detectBullet(body: String): String? {
    if (body.length < 2) return null
    val marker = body[0]
    if (marker != '-' && marker != '*' && marker != '+') return null
    if (body[1] != ' ' && body[1] != '\t') return null
    return body.substring(2).trim()
}

private class NumberedItem(val number: String, val text: String)

/**
 * Detects "1. text", "1) text", "1.  text" (2+ spaces are fine) and also
 * "1." alone (the text has not arrived yet).
 * "1.5 apples" is NOT a list item (no space after the dot).
 */
private fun detectNumbered(body: String): NumberedItem? {
    var digits = 0
    while (digits < body.length && digits < 9 && body[digits] in '0'..'9') digits++
    if (digits == 0 || digits >= body.length) return null
    val delimiter = body[digits]
    if (delimiter != '.' && delimiter != ')') return null
    val after = digits + 1
    if (after < body.length && body[after] != ' ' && body[after] != '\t') return null
    return NumberedItem(body.substring(0, after), body.substring(after).trim())
}

private fun joinLines(first: String, second: String): String =
    if (first.isEmpty()) second else first + "\n" + second

// ---- table helpers ----------------------------------------------------------

/**
 * Splits "| a | b |" into ["a", "b"]. The outer pipes are optional.
 * "\|" is a literal pipe inside a cell.
 */
private fun splitRow(trimmed: String): List<String> {
    var s = trimmed
    if (s.startsWith("|")) s = s.substring(1)
    val cells = ArrayList<String>()
    val current = StringBuilder()
    var k = 0
    while (k < s.length) {
        val ch = s[k]
        if (ch == '\\' && k + 1 < s.length && s[k + 1] == '|') {
            current.append('|')
            k += 2
        } else if (ch == '|') {
            cells.add(current.toString().trim())
            current.setLength(0)
            k++
        } else {
            current.append(ch)
            k++
        }
    }
    // Text after the last pipe is one more cell (row without closing pipe).
    val last = current.toString().trim()
    if (last.isNotEmpty() || cells.isEmpty()) cells.add(last)
    return cells
}

/** Is this line like "| --- | :---: | ---: |" ? */
private fun isDelimiterRow(trimmed: String): Boolean {
    if (!trimmed.contains('|')) return false
    val cells = splitRow(trimmed)
    if (cells.isEmpty()) return false
    for (cell in cells) {
        var core = cell
        if (core.startsWith(":")) core = core.substring(1)
        if (core.endsWith(":")) core = core.substring(0, core.length - 1)
        if (core.isEmpty()) return false
        for (ch in core) {
            if (ch != '-') return false
        }
    }
    return true
}

/** 0 = left, 1 = center (":---:"), 2 = right ("---:"). */
private fun parseAligns(delimiter: String, columns: Int): List<Int> {
    val cells = splitRow(delimiter)
    val result = ArrayList<Int>()
    for (c in 0 until columns) {
        val cell = if (c < cells.size) cells[c] else ""
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        result.add(if (left && right) 1 else if (right) 2 else 0)
    }
    return result
}

/** Make a row have exactly [columns] cells (add empty ones or cut extras). */
private fun fitCells(cells: List<String>, columns: Int): List<String> {
    val result = ArrayList<String>()
    for (c in 0 until columns) {
        result.add(if (c < cells.size) cells[c] else "")
    }
    return result
}

// ============================================================================
//  4. INLINE PARSER  (bold, italic, code, strike, links, escapes)
// ============================================================================

// Limits that keep parsing fast and safe.
private const val MAX_INLINE_DEPTH = 6      // how deep **_nested_** styles may go
private const val MAX_LABEL_LENGTH = 300    // longest link label we try to match
private const val MAX_URL_LENGTH = 2000     // longest link URL we try to match
private const val MAX_TAG_LENGTH = 200      // longest HTML tag like <span style="...">

// Characters that a backslash can escape:  \*  \_  \`  \\  \#  \[  ...
private const val ESCAPABLE = "\\`*_{}[]()#+-.!~|><\$"   // MATH: "\$" shows a normal $

/**
 * Turns one block's text into an AnnotatedString. Never throws:
 * on any error we return the text unchanged (plain).
 */
private fun parseInline(text: String, colors: InlineColors): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    return try {
        buildAnnotatedString {
            InlineParser(text, this, colors).parse()
        }
    } catch (e: Exception) {
        AnnotatedString(text)
    }
}

/**
 * The inline parser. It walks over the text from left to right.
 *
 * KEY IDEA for streaming: when we see an opening marker (like "**"), we FIRST
 * look for its closing marker. Only if the closer exists do we apply the
 * style. If not, the marker is written as normal text. So nothing ever
 * disappears while the closer has not arrived yet.
 *
 * SPEED: when a search for a closer fails, we remember "no closer exists
 * after position P" (failedFrom). Later searches of the same kind are
 * answered instantly. This keeps the whole parse roughly linear, even for
 * nasty input like "* * * * * ...".
 */
private class InlineParser(
    private val src: String,
    private val out: AnnotatedString.Builder,
    colors: InlineColors
) {
    private val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 0.9.em,
        background = colors.codeBackground,
        color = colors.codeText
    )
    private val linkStyle = SpanStyle(
        color = colors.link,
        textDecoration = TextDecoration.Underline
    )
    private val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    private val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    private val boldItalicStyle =
        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    private val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)
    private val underlineStyle = SpanStyle(textDecoration = TextDecoration.Underline)

    // key -> "a search that started at this position (or earlier) failed"
    private val failedFrom = HashMap<String, Int>()

    fun parse() {
        parseRange(0, src.length, 0)
    }

    /** Parse src[from, to) and append the result to [out]. */
    private fun parseRange(from: Int, to: Int, depth: Int) {
        // Too deeply nested? Stop styling, just write the text.
        if (depth > MAX_INLINE_DEPTH) {
            out.append(src.substring(from, to))
            return
        }
        var i = from
        while (i < to) {
            val c = src[i]
            val next = when (c) {
                '\\' -> handleBackslash(i, to)
                '$' -> handleDollar(i, to)
                '`' -> handleCode(i, to)
                '*', '_' -> handleEmphasis(i, to, depth)
                '~' -> handleStrike(i, to, depth)
                '[' -> handleLink(i, to, depth)
                '<' -> handleHtml(i, to, depth)
                else -> {
                    out.append(c)
                    i + 1
                }
            }
            // Safety net: always move forward, so we can never loop forever.
            i = if (next > i) next else i + 1
        }
    }

    // ---- backslash escape ---------------------------------------------------

    private fun handleBackslash(i: Int, to: Int): Int {
        // MATH: "\( ... \)" and "\[ ... \]" are formulas. Only when the closing
        // sign exists; otherwise the normal escape rule below is used.
        if (i + 1 < to && (src[i + 1] == '(' || src[i + 1] == '[')) {
            val span = findInlineMath(src, i, to)
            if (span != null) {
                out.append(latexToAnnotated(span.latex))
                return span.end
            }
        }
        if (i + 1 < to && ESCAPABLE.indexOf(src[i + 1]) >= 0) {
            out.append(src[i + 1]) // show the escaped character literally
            return i + 2
        }
        out.append('\\') // a normal backslash stays
        return i + 1
    }

    // ---- MATH: $formula$ and $$formula$$ inside a sentence --------------------

    /**
     * A "$" starts a formula only when a matching closing sign exists and
     * the text looks like math (see findInlineMath in MathText.kt: prices such
     * as "$5 and $10" are NOT formulas). The formula is converted to styled
     * text (symbols, small raised/lowered parts...) and written in place.
     * If it is not a formula, the dollar sign(s) stay as normal text.
     */
    private fun handleDollar(i: Int, to: Int): Int {
        val span = findInlineMath(src, i, to)
        if (span != null) {
            out.append(latexToAnnotated(span.latex))
            return span.end
        }
        // Not a formula: write the whole run of $ signs as plain text.
        val run = runLength('$', i, to)
        out.append(src.substring(i, i + run))
        return i + run
    }

    // ---- `inline code` --------------------------------------------------------

    private fun handleCode(i: Int, to: Int): Int {
        val ticks = runLength('`', i, to)
        val end = codeSpanEnd(i, to)
        if (end < 0) {
            // No closing backticks: stay plain text.
            out.append(src.substring(i, i + ticks))
            return i + ticks
        }
        var content = src.substring(i + ticks, end - ticks).replace('\n', ' ')
        // "` a `" -> "a" (one space on each side is removed)
        if (content.length >= 2 && content.startsWith(" ") && content.endsWith(" ") &&
            content.isNotBlank()
        ) {
            content = content.substring(1, content.length - 1)
        }
        out.pushStyle(codeStyle)
        out.append(content) // NO other parsing inside code
        out.pop()
        return end
    }

    /**
     * Opening backtick run starts at [i]. Returns the index just AFTER the
     * closing run (same length), or -1 if there is none.
     */
    private fun codeSpanEnd(i: Int, to: Int): Int {
        val ticks = runLength('`', i, to)
        val start = i + ticks
        val key = "c$ticks@$to"
        if (failedBefore(key, start)) return -1
        var p = start
        while (p < to) {
            if (src[p] == '`') {
                val run = runLength('`', p, to)
                if (run == ticks) return p + run
                p += run
            } else {
                p++
            }
        }
        rememberFailure(key, start)
        return -1
    }

    // ---- **bold**, *italic*, ***both***, __bold__, _italic_ ------------------

    private fun handleEmphasis(i: Int, to: Int, depth: Int): Int {
        val marker = src[i]
        val run = runLength(marker, i, to)
        val afterRun = i + run

        // Can this run OPEN a style?
        //  - the next char must exist and must not be a space
        //  - "_" must not be inside a word (snake_case_name stays plain)
        var canOpen = afterRun < to && !src[afterRun].isWhitespace()
        if (marker == '_' && i > 0 && src[i - 1].isLetterOrDigit()) canOpen = false

        if (canOpen && depth < MAX_INLINE_DEPTH) {
            // Try the longest marker first (3 = bold+italic), then shorter.
            var length = minOf(run, 3)
            while (length >= 1) {
                val close = findCloser(marker, length, i + length, to)
                if (close >= 0) {
                    out.pushStyle(
                        when (length) {
                            1 -> italicStyle
                            2 -> boldStyle
                            else -> boldItalicStyle
                        }
                    )
                    parseRange(i + length, close, depth + 1)
                    out.pop()
                    return close + length
                }
                length--
            }
        }
        // No style: the markers are normal text.
        out.append(src.substring(i, afterRun))
        return afterRun
    }

    // ---- ~~strikethrough~~ ----------------------------------------------------

    private fun handleStrike(i: Int, to: Int, depth: Int): Int {
        val run = runLength('~', i, to)
        if (run == 2 && depth < MAX_INLINE_DEPTH && i + 2 < to && !src[i + 2].isWhitespace()) {
            val close = findCloser('~', 2, i + 2, to)
            if (close >= 0) {
                out.pushStyle(strikeStyle)
                parseRange(i + 2, close, depth + 1)
                out.pop()
                return close + 2
            }
        }
        out.append(src.substring(i, i + run))
        return i + run
    }

    /**
     * Looks for the closing marker (a run of exactly [length] times [marker])
     * starting at [start]. Returns its index or -1.
     * A valid closer: the content is not empty, the char before it is not a
     * space, and (for "_") the char after it is not a letter or digit.
     * Escapes (\*) and `code spans` are skipped on the way.
     */
    private fun findCloser(marker: Char, length: Int, start: Int, to: Int): Int {
        val key = "m$marker$length@$to"
        if (failedBefore(key, start)) return -1
        var j = start
        while (j < to) {
            val ch = src[j]
            if (ch == '\\') {
                j += 2
                continue
            }
            if (ch == '`') {
                val end = codeSpanEnd(j, to)
                j = if (end > 0) end else j + runLength('`', j, to)
                continue
            }
            if (ch == marker) {
                val run = runLength(marker, j, to)
                if (run == length && j > start && !src[j - 1].isWhitespace() &&
                    closerAllowedAfter(marker, j + run, to)
                ) {
                    return j
                }
                j += run
                continue
            }
            j++
        }
        rememberFailure(key, start)
        return -1
    }

    private fun closerAllowedAfter(marker: Char, after: Int, to: Int): Boolean =
        marker != '_' || after >= to || !src[after].isLetterOrDigit()

    // ---- small HTML: <br> <b> <i> <u> <s> <span style="color:.."> <font color=".."> ----

    /**
     * A "<" starts a tag only if it is one of the few tags we know AND (for
     * paired tags) the closing tag exists. Otherwise "<" is normal text, so
     * things like "a < b" or "List<String>" are never damaged.
     */
    private fun handleHtml(i: Int, to: Int, depth: Int): Int {
        val tagEnd = findTagEnd(i, to)
        if (tagEnd < 0 || depth >= MAX_INLINE_DEPTH) {
            out.append('<')
            return i + 1
        }
        var inner = src.substring(i + 1, tagEnd).trim()
        var selfClosing = false
        if (inner.endsWith("/")) {
            selfClosing = true
            inner = inner.substring(0, inner.length - 1).trim()
        }
        var nameLength = 0
        while (nameLength < inner.length && inner[nameLength].isLetterOrDigit()) nameLength++
        // nameLength == 0 also covers closing tags like "</b>" (they start with "/").
        if (nameLength == 0 || (inner.isNotEmpty() && inner[0] == '/')) {
            out.append('<')
            return i + 1
        }
        val name = inner.substring(0, nameLength).lowercase()

        // <br>, <br/>, <br /> -> a new line.
        if (name == "br") {
            out.append('\n')
            return tagEnd + 1
        }

        val style = htmlStyleFor(name, inner.substring(nameLength))
        if (style == null) {
            out.append('<') // unknown tag: stay plain text
            return i + 1
        }
        if (selfClosing) return tagEnd + 1

        val closeStart = findHtmlClose(name, tagEnd + 1, to)
        if (closeStart < 0) {
            out.append('<') // closing tag has not arrived (yet): stay plain
            return i + 1
        }
        val closeEnd = matchTag(closeStart, name, true, to)
        out.pushStyle(style)
        parseRange(tagEnd + 1, closeStart, depth + 1)
        out.pop()
        return closeEnd + 1
    }

    /** The style for a supported tag, or null if we do not support the tag. */
    private fun htmlStyleFor(name: String, attributes: String): SpanStyle? = when (name) {
        "b", "strong" -> boldStyle
        "i", "em" -> italicStyle
        "u", "ins" -> underlineStyle
        "s", "del", "strike" -> strikeStyle
        "span", "font" -> {
            val color = extractColor(attributes)
            if (color != null) SpanStyle(color = color) else SpanStyle()
        }
        else -> null
    }

    /** Index of the ">" that ends the tag starting at [open], or -1. */
    private fun findTagEnd(open: Int, to: Int): Int {
        val limit = minOf(to, open + 1 + MAX_TAG_LENGTH)
        var p = open + 1
        while (p < limit) {
            val ch = src[p]
            if (ch == '>') return p
            if (ch == '<' || ch == '\n') return -1
            p++
        }
        return -1
    }

    /**
     * Does a tag called [name] start at [lt] (a "<")? If [closing] is true
     * we look for "</name>", otherwise for "<name ...>".
     * Returns the index of its ">" or -1.
     */
    private fun matchTag(lt: Int, name: String, closing: Boolean, to: Int): Int {
        var p = lt + 1
        if (closing) {
            if (p >= to || src[p] != '/') return -1
            p++
        }
        if (!src.startsWith(name, p, ignoreCase = true)) return -1
        p += name.length
        if (p >= to) return -1
        if (closing) {
            while (p < to && src[p] == ' ') p++
            return if (p < to && src[p] == '>') p else -1
        }
        val ch = src[p]
        if (ch != '>' && ch != ' ' && ch != '\t') return -1 // "<spanner>" is not "<span>"
        return findTagEnd(lt, to)
    }

    /**
     * Finds the "<" of the closing tag that matches an opening tag [name]
     * whose content starts at [start]. Same-name tags inside are counted.
     */
    private fun findHtmlClose(name: String, start: Int, to: Int): Int {
        val key = "h$name@$to"
        if (failedBefore(key, start)) return -1
        var depth = 0
        var p = start
        while (p < to) {
            val lt = src.indexOf('<', p)
            if (lt < 0 || lt >= to) break
            if (matchTag(lt, name, true, to) >= 0) {
                if (depth == 0) return lt
                depth--
            } else if (matchTag(lt, name, false, to) >= 0) {
                depth++
            }
            p = lt + 1
        }
        rememberFailure(key, start)
        return -1
    }

    // ---- [label](url) ---------------------------------------------------------

    private fun handleLink(i: Int, to: Int, depth: Int): Int {
        // "![alt](url)" is an image: images are not supported, stay plain.
        if (i > 0 && src[i - 1] == '!') {
            out.append('[')
            return i + 1
        }
        val labelEnd = findLabelEnd(i, to)
        if (labelEnd > 0 && labelEnd + 1 < to && src[labelEnd + 1] == '(') {
            val urlEnd = findUrlEnd(labelEnd + 1, to)
            if (urlEnd > 0) {
                // Show only the label: underlined, primary color. Not clickable.
                out.pushStyle(linkStyle)
                parseRange(i + 1, labelEnd, depth + 1)
                out.pop()
                return urlEnd + 1
            }
        }
        // Not a complete link (yet): stay plain text.
        out.append('[')
        return i + 1
    }

    /** Index of the "]" that matches the "[" at [open], or -1. */
    private fun findLabelEnd(open: Int, to: Int): Int {
        val limit = minOf(to, open + 1 + MAX_LABEL_LENGTH)
        var nesting = 0
        var p = open + 1
        while (p < limit) {
            when (src[p]) {
                '\\' -> p++ // skip the escaped character
                '[' -> nesting++
                ']' -> {
                    if (nesting == 0) return if (p == open + 1) -1 else p // empty label = no link
                    nesting--
                }
            }
            p++
        }
        return -1
    }

    /** Index of the ")" that matches the "(" at [open], or -1. */
    private fun findUrlEnd(open: Int, to: Int): Int {
        val limit = minOf(to, open + 1 + MAX_URL_LENGTH)
        var nesting = 0
        var p = open + 1
        while (p < limit) {
            when (src[p]) {
                '\n' -> return -1
                '(' -> nesting++
                ')' -> {
                    if (nesting == 0) return p
                    nesting--
                }
            }
            p++
        }
        return -1
    }

    // ---- tiny helpers ---------------------------------------------------------

    /** How many times [ch] repeats starting at [start] (never past [to]). */
    private fun runLength(ch: Char, start: Int, to: Int): Int {
        var p = start
        while (p < to && src[p] == ch) p++
        return p - start
    }

    private fun failedBefore(key: String, start: Int): Boolean {
        val failed = failedFrom[key] ?: return false
        return start >= failed
    }

    private fun rememberFailure(key: String, start: Int) {
        val old = failedFrom[key]
        if (old == null || start < old) failedFrom[key] = start
    }
}

// ---- text colors (used by <span style="color:..."> and <font color="...">) ----

/**
 * Looks for "color: xxx" or "color=xxx" inside the attribute text of a tag.
 * "background-color" is skipped on purpose. Returns null if there is no
 * color we understand.
 */
private fun extractColor(attributes: String): Color? {
    val text = attributes.lowercase()
    var from = 0
    while (from < text.length) {
        val at = text.indexOf("color", from)
        if (at < 0) return null
        from = at + 5
        // Skip "background-color", "bgcolor", ...
        if (at > 0 && (text[at - 1] == '-' || text[at - 1].isLetter())) continue
        var p = from
        while (p < text.length && text[p] == ' ') p++
        if (p >= text.length || (text[p] != '=' && text[p] != ':')) continue
        p++
        while (p < text.length && (text[p] == ' ' || text[p] == '"' || text[p] == '\'')) p++
        val valueStart = p
        while (p < text.length && text[p] != '"' && text[p] != '\'' &&
            text[p] != ';' && text[p] != ' '
        ) p++
        val color = parseColor(text.substring(valueStart, p))
        if (color != null) return color
    }
    return null
}

/**
 * A color name (soft shades that are readable on a dark theme) or a hex
 * code: #RGB or #RRGGBB. Unknown text -> null (the tag then has no color).
 */
private fun parseColor(token: String): Color? {
    val t = token.trim().lowercase()
    if (t.isEmpty()) return null
    if (t.startsWith("#")) return parseHexColor(t.substring(1))
    return when (t) {
        "red" -> Color(0xFFEF5350)
        "orange" -> Color(0xFFFFA726)
        "yellow" -> Color(0xFFFFEE58)
        "gold" -> Color(0xFFFFD54F)
        "lime" -> Color(0xFFD4E157)
        "green" -> Color(0xFF66BB6A)
        "teal" -> Color(0xFF26A69A)
        "cyan", "aqua" -> Color(0xFF26C6DA)
        "blue" -> Color(0xFF64B5F6)
        "purple", "violet" -> Color(0xFFBA68C8)
        "pink" -> Color(0xFFF48FB1)
        "magenta" -> Color(0xFFEC407A)
        "brown" -> Color(0xFFA1887F)
        "gray", "grey" -> Color(0xFF9E9E9E)
        "white" -> Color(0xFFFFFFFF)
        "black" -> Color(0xFF000000)
        else -> null
    }
}

private fun parseHexColor(hex: String): Color? {
    val full = if (hex.length == 3) {
        "" + hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2]
    } else {
        hex
    }
    if (full.length != 6) return null
    var value = 0L
    for (ch in full) {
        val digit = ch.digitToIntOrNull(16) ?: return null
        value = value * 16 + digit
    }
    return Color(0xFF000000L or value)
}

// ============================================================================
//  5. DRAWING  (one small composable per block type)
// ============================================================================

/** Draws a Text from inline-Markdown. The parse is cached per text. */
@Composable
private fun InlineText(
    text: String,
    look: Look,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified
) {
    val annotated = remember(text, look.inline) { parseInline(text, look.inline) }
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
        textAlign = textAlign
    )
}

/** Draws one non-list block. */
@Composable
private fun BlockView(block: Block, look: Look) {
    when (block) {
        is Block.Heading -> {
            val headingStyle = when (block.level) {
                1 -> look.heading1
                2 -> look.heading2
                else -> look.heading3 // levels 3..6 look the same
            }
            InlineText(block.text, look, headingStyle, look.color, Modifier.fillMaxWidth())
        }
        is Block.Paragraph ->
            InlineText(block.text, look, look.body, look.color, Modifier.fillMaxWidth())
        is Block.Code -> CodeView(block.language, block.code, look)
        Block.Rule -> RuleView(look)
        is Block.Quote -> QuoteView(block.text, look)
        is Block.Table -> TableView(block, look)
        is Block.Math -> MathBlockView(block.latex, look)
        // List items are drawn by ListGroup (see MarkdownText).
        is Block.Bullet, is Block.Numbered -> Unit
    }
}

/**
 * MATH: a display formula on its own. Centered. If it is wider than the
 * screen it scrolls sideways (like code blocks) instead of wrapping.
 * The LaTeX is converted by latexToAnnotated (MathText.kt); the conversion
 * is cached per text.
 */
@Composable
private fun MathBlockView(latex: String, look: Look) {
    val annotated = remember(latex) { latexToAnnotated(latex) }
    // Outer Box centers the formula. The Text inside scrolls sideways: when it
    // is narrow it takes only its own width (so it stays centered), when it is
    // wider than the box it fills the box and scrolls.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = annotated,
            style = look.body,
            color = look.color,
            textAlign = TextAlign.Center,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}

/** A run of list items: 6 dp between items. */
@Composable
private fun ListGroup(items: List<Block>, look: Look) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (item in items) {
            when (item) {
                is Block.Bullet -> ListRow(
                    level = item.level,
                    marker = if (item.level == 0) "\u2022" else "\u25E6", // • or ◦
                    markerWidth = 16.dp,
                    text = item.text,
                    look = look
                )
                is Block.Numbered -> ListRow(
                    level = item.level,
                    marker = item.number,
                    // Wider marker for longer numbers like "10." so text still lines up.
                    markerWidth = (8 * item.number.length + 8).dp,
                    text = item.text,
                    look = look
                )
                else -> Unit
            }
        }
    }
}

/**
 * One list item = [marker] + [text] in a Row. Because the marker has a FIXED
 * width, wrapped lines of the text line up under the first line
 * (a "hanging indent").
 */
@Composable
private fun ListRow(
    level: Int,
    marker: String,
    markerWidth: Dp,
    text: String,
    look: Look
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (16 * level).dp) // 16 dp per nesting level
    ) {
        Text(
            text = marker,
            style = look.body,
            color = look.color,
            softWrap = false,
            modifier = Modifier.width(markerWidth)
        )
        InlineText(text, look, look.body, look.color, Modifier.weight(1f))
    }
}

/** Fenced code block: monospace, rounded, scrolls sideways, shows the language on top. */
@Composable
private fun CodeView(language: String, code: String, look: Look) {
    // COPY: the phone's clipboard, and a flag that is true for 1.5 seconds
    // after a tap (the button then says "Copied").
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(look.codeBackground)
            .padding(12.dp)
    ) {
        // COPY: top row = language name on the left, "Copy" button on the
        // right. DisableSelection keeps these two words OUT of a text
        // selection, so when the user selects code, only the code is copied.
        DisableSelection {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = language, style = look.labelStyle, color = look.codeLabel)
                Text(
                    text = if (copied) "Copied" else "Copy",
                    style = look.labelStyle,
                    color = look.codeLabel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(code))   // ONLY the code
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // softWrap = false + horizontalScroll: long lines do NOT wrap, you scroll instead.
        Text(
            text = code,
            style = look.codeStyle,
            color = look.codeText,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}

/** Thin horizontal divider line. */
@Composable
private fun RuleView(look: Look) {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        thickness = 1.dp,
        color = look.rule
    )
}

/** Block quote: a 3 dp colored bar on the left and slightly dimmed text. */
@Composable
private fun QuoteView(text: String, look: Look) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min) // so the bar is as tall as the text
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(look.quoteBar, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        InlineText(text, look, look.body, look.quoteText, Modifier.weight(1f))
    }
}

// ---- tables -----------------------------------------------------------------

/**
 * Table: a header row on top, then the body rows. Each column has a fixed
 * width (guessed from the longest text in it), so the table can be wider than
 * the screen. In that case you scroll it sideways.
 */
@Composable
private fun TableView(table: Block.Table, look: Look) {
    val widths = remember(table) { columnWidths(table) }
    // Total width = all columns + the 1 dp lines between them.
    val totalWidth = remember(widths) {
        var sum = 0.dp
        for (w in widths) sum += w
        sum + (widths.size - 1).coerceAtLeast(0).dp
    }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier = Modifier.clip(shape).border(1.dp, look.rule, shape)) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TableRowView(table.header, table.aligns, widths, look.tableHeader,
                look.tableHeaderBackground, look)
            for (row in table.rows) {
                // A thin line between rows.
                Box(modifier = Modifier.width(totalWidth).height(1.dp).background(look.rule))
                TableRowView(row, table.aligns, widths, look.tableBody, Color.Transparent, look)
            }
        }
    }
}

/** One table row: cells side by side with a thin vertical line between them. */
@Composable
private fun TableRowView(
    cells: List<String>,
    aligns: List<Int>,
    widths: List<Dp>,
    style: TextStyle,
    background: Color,
    look: Look
) {
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min) // all cells of the row get the same height
            .background(background)
    ) {
        for (c in widths.indices) {
            if (c > 0) {
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(look.rule))
            }
            val align = when (aligns.getOrNull(c)) {
                1 -> TextAlign.Center
                2 -> TextAlign.End
                else -> TextAlign.Start
            }
            Box(
                modifier = Modifier
                    .width(widths[c])
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                InlineText(
                    text = cells.getOrNull(c) ?: "",
                    look = look,
                    style = style,
                    color = look.color,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = align
                )
            }
        }
    }
}

/** Column width guess: about 7 dp per character, between 72 dp and 240 dp. */
private fun columnWidths(table: Block.Table): List<Dp> {
    val result = ArrayList<Dp>()
    for (c in table.header.indices) {
        var longest = table.header[c].length
        for (row in table.rows) {
            if (c < row.size && row[c].length > longest) longest = row[c].length
        }
        result.add((longest * 7 + 24).coerceIn(72, 240).dp)
    }
    return result
}
