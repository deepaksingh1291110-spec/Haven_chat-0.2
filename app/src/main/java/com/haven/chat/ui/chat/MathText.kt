/*
 * ============================================================================
 *  MathText.kt  -  LaTeX math to readable styled text (no library)
 * ============================================================================
 *
 *  WHAT
 *  ----
 *  Gemma writes math in LaTeX, for example
 *        $\binom{m}{k} = \frac{m!}{k!(m-k)!}$   or   $\text{SO}_4^{2-}$
 *  This file turns such text into what people read:
 *        C(m, k) = m!/(k!(m − k)!)              or   SO with small 4 and 2⁻
 *
 *  It has THREE public (module-wide) helpers, used by MarkdownText.kt:
 *
 *    latexToAnnotated(latex)       the converter. Gives an AnnotatedString
 *                                  (text + styles) that Compose's Text() draws.
 *    findInlineMath(src, i, to)    "does a formula start at position i?"
 *                                  Understands $...$, $$...$$, \(...\).
 *    detectMathBlock(lines, i)     "does a display formula start at line i?"
 *                                  Understands $$ ... $$ and \[ ... \] that
 *                                  fill whole lines.
 *
 *  HOW THE CONVERTER WORKS
 *  -----------------------
 *  A small hand-written parser reads the LaTeX from left to right:
 *    - letters           -> italic (like in a math book): x, n, k
 *    - digits, ( ) !     -> normal
 *    - + - = < >         -> real symbols with a little space around them
 *    - \alpha \times \le -> the real symbols: α × ≤   (tables below)
 *    - x^2  x_i  x^{2-}  -> SMALL RAISED / LOWERED text (real baseline shift,
 *                           so it works for ANY content, not only digits)
 *    - \text{...}        -> normal (upright) text, spaces kept
 *    - \frac{a}{b}       -> a/b   (brackets are added when needed)
 *    - \binom{m}{k}      -> C(m, k)
 *    - \sqrt{x}          -> √x   (or √(x + 1) when it is longer)
 *    - \hat{x} \bar{x}   -> x with an accent mark (single characters)
 *    - \begin{pmatrix}.. -> simple: brackets, rows on separate lines
 *    - \begin{array}{cc} -> same, the column code {cc} is skipped (not printed)
 *  Anything it does not know is shown as plain readable text (the backslash
 *  is dropped), so a formula never turns into garbage or a crash.
 *
 *  NOT SUPPORTED (shown in simplified form): stacked fractions, real
 *  matrices and aligned equations, big brackets, drawings.
 *
 *  SAFETY
 *  ------
 *  - Never throws: any problem returns the original text.
 *  - Nesting and length are limited, so strange input cannot hang the app.
 *  - Streaming: findInlineMath / detectMathBlock only report a formula when
 *    its CLOSING sign has arrived. Until then the text stays plain, so a
 *    half-written formula never flickers into something strange.
 * ============================================================================
 */
package com.haven.chat.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.em

// ============================================================================
//  1. PUBLIC HELPERS
// ============================================================================

// Limits that keep everything fast and safe.
private const val MAX_MATH_LENGTH = 600        // longest inline formula we look for
private const val MAX_LATEX_LENGTH = 3000      // longest formula we convert
private const val MAX_BLOCK_LINES = 30         // longest display formula (lines)
private const val MAX_NESTING = 60             // nested { { { } } }
private const val MAX_LEVEL = 12               // nested \frac{\frac{..}..}

/**
 * Converts LaTeX math (without the $ signs) into styled text.
 * Never throws. Empty input gives empty text.
 */
internal fun latexToAnnotated(latex: String): AnnotatedString {
    val text = latex.trim()
    if (text.isEmpty()) return AnnotatedString("")
    // Too long to be a normal formula: show as it is.
    if (text.length > MAX_LATEX_LENGTH) return AnnotatedString(text)
    return try {
        MathParser(text).parse()
    } catch (e: Exception) {
        AnnotatedString(text)
    }
}

/** A formula found inside a line of text. [end] = index just AFTER its closing sign. */
internal class MathSpan(val latex: String, val end: Int)

/**
 * Is there an inline formula that starts at src[i]? Looks only inside
 * src[i, to). Returns null when there is none, or when the closing sign
 * has not arrived yet (streaming).
 *
 * Understood:   $$ ... $$      $ ... $      \( ... \)      \[ ... \]
 *
 * The single-dollar rule protects normal text with prices:
 *   - the character after the opening $ must not be a space,
 *   - the character before the closing $ must not be a space,
 *   - the character after the closing $ must not be a digit.
 * So "it costs $5 and $10" is NOT math, but "$x + 1$" is.
 */
internal fun findInlineMath(src: String, i: Int, to: Int): MathSpan? {
    if (i < 0 || i >= to) return null
    val c = src[i]

    if (c == '$') {
        // ---- $$ ... $$ (written inside a sentence) ----
        if (i + 1 < to && src[i + 1] == '$') {
            val close = src.indexOf("\$\$", i + 2)
            if (close < 0 || close + 2 > to) return null
            if (close - i > MAX_MATH_LENGTH) return null
            val body = src.substring(i + 2, close)
            if (body.isBlank()) return null
            return MathSpan(body, close + 2)
        }

        // ---- $ ... $ ----
        if (i + 1 >= to) return null
        if (src[i + 1].isWhitespace()) return null
        val limit = minOf(to, i + 1 + MAX_MATH_LENGTH)
        var j = i + 1
        while (j < limit) {
            val ch = src[j]
            if (ch == '\\') {          // an escaped character (\$ , \{ ...): skip both
                j += 2
                continue
            }
            if (ch == '$') {
                // The FIRST unescaped $ decides: it either closes the formula
                // or this was not a formula at all (for example a price).
                val okBefore = j > i + 1 && !src[j - 1].isWhitespace()
                val okAfter = j + 1 >= to || !src[j + 1].isDigit()
                return if (okBefore && okAfter) MathSpan(src.substring(i + 1, j), j + 1) else null
            }
            j++
        }
        return null
    }

    // ---- \( ... \)   and   \[ ... \] ----
    if (c == '\\' && i + 1 < to) {
        val closer = when (src[i + 1]) {
            '(' -> "\\)"
            '[' -> "\\]"
            else -> return null
        }
        val close = src.indexOf(closer, i + 2)
        if (close < 0 || close + 2 > to) return null
        if (close - i > MAX_MATH_LENGTH) return null
        val body = src.substring(i + 2, close)
        if (body.isBlank()) return null
        return MathSpan(body, close + 2)
    }
    return null
}

/** A display formula made of whole lines. [nextIndex] = the first line AFTER it. */
internal class MathBlockResult(val latex: String, val nextIndex: Int)

/**
 * Does a display formula start at lines[i]?
 *
 *   $$ x = 1 $$            (one line)
 *   $$                     (several lines)
 *   x = 1
 *   $$
 *   \[ x = 1 \]            (same with \[ and \])
 *
 * The closing sign must END its line. If text follows it ("$$x$$, and ..."),
 * the formula belongs to a sentence, so it is NOT a block (returns null) and
 * the inline parser draws it instead.
 * If the closing sign has not arrived yet (streaming), returns null.
 */
internal fun detectMathBlock(lines: List<String>, i: Int): MathBlockResult? {
    if (i < 0 || i >= lines.size) return null
    val first = lines[i].trim()
    val open: String
    val close: String
    when {
        first.startsWith("\$\$") -> { open = "\$\$"; close = "\$\$" }
        first.startsWith("\\[") -> { open = "\\["; close = "\\]" }
        else -> return null
    }

    val afterOpen = first.substring(open.length)

    // Whole formula on one line.
    val sameLine = afterOpen.indexOf(close)
    if (sameLine >= 0) {
        if (afterOpen.substring(sameLine + close.length).isNotBlank()) return null
        val body = afterOpen.substring(0, sameLine)
        return if (body.isBlank()) null else MathBlockResult(body, i + 1)
    }

    // Formula over several lines: collect until the closing sign.
    val body = StringBuilder(afterOpen)
    var k = i + 1
    while (k < lines.size && k <= i + MAX_BLOCK_LINES) {
        val line = lines[k].trim()
        val at = line.indexOf(close)
        if (at >= 0) {
            if (line.substring(at + close.length).isNotBlank()) return null
            body.append('\n').append(line.substring(0, at))
            return MathBlockResult(body.toString(), k + 1)
        }
        body.append('\n').append(line)
        k++
    }
    return null   // no closing sign (yet)
}

// ============================================================================
//  2. SYMBOL TABLES  (LaTeX command name -> the real symbol)
// ============================================================================

// Lowercase Greek letters are drawn in italic, like variables.
private val GREEK_LOWER = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο",
    "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
    "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
    "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω"
)

// Symbols that stand alone like a letter or digit (upright).
private val ORDINARY = mapOf(
    // uppercase Greek
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
    "Omega" to "Ω",
    // common signs
    "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀",
    "exists" to "∃", "nexists" to "∄", "emptyset" to "∅", "varnothing" to "∅",
    "neg" to "¬", "lnot" to "¬", "angle" to "∠", "degree" to "°", "circ" to "∘",
    "prime" to "′", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
    "aleph" to "ℵ", "therefore" to "∴", "because" to "∵", "square" to "□",
    "triangle" to "△", "checkmark" to "✓", "top" to "⊤", "bot" to "⊥",
    "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋",
    "lceil" to "⌈", "rceil" to "⌉", "lbrace" to "{", "rbrace" to "}",
    "vert" to "|", "Vert" to "‖", "backslash" to "\\", "dagger" to "†",
    "ddagger" to "‡", "S" to "§", "P" to "¶", "copyright" to "©",
    "dots" to "…"
)

// "Three dots" signs: drawn with a little space around them.
private val ELLIPSIS = mapOf(
    "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱"
)

// Calculation signs between two things (space around, unless used as a sign).
private val BINARY = mapOf(
    "times" to "×", "cdot" to "·", "pm" to "±", "mp" to "∓", "div" to "÷",
    "ast" to "∗", "star" to "⋆", "bullet" to "•", "cup" to "∪", "cap" to "∩",
    "wedge" to "∧", "land" to "∧", "vee" to "∨", "lor" to "∨", "oplus" to "⊕",
    "otimes" to "⊗", "setminus" to "∖", "odot" to "⊙"
)

// Signs that compare or connect (more space around them).
private val RELATIONS = mapOf(
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠",
    "neq" to "≠", "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃",
    "cong" to "≅", "propto" to "∝", "in" to "∈", "notin" to "∉", "ni" to "∋",
    "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
    "ll" to "≪", "gg" to "≫", "perp" to "⊥", "parallel" to "∥", "mid" to "∣",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←",
    "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
    "Leftrightarrow" to "⇔", "iff" to "⟺", "implies" to "⟹", "impliedby" to "⟸",
    "mapsto" to "↦", "longrightarrow" to "⟶", "longleftarrow" to "⟵",
    "uparrow" to "↑", "downarrow" to "↓", "rightleftharpoons" to "⇌"
)

// Big signs that carry limits (written with _ and ^ after them).
private val BIG = mapOf(
    "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬",
    "iiint" to "∭", "oint" to "∮", "bigcup" to "⋃", "bigcap" to "⋂",
    "bigoplus" to "⨁", "bigotimes" to "⨂"
)

// Function names: written upright, with a little space after.
private val FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
    "sinh", "cosh", "tanh", "coth", "log", "ln", "lg", "exp", "lim", "limsup",
    "liminf", "max", "min", "sup", "inf", "det", "gcd", "deg", "dim", "ker",
    "arg", "mod", "Pr", "hom", "tr", "rank"
)

// Commands that only change the look in real LaTeX. We just skip them.
private val IGNORED = setOf(
    "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
    "limits", "nolimits", "nonumber", "notag", "hline", "relax", "middle",
    "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr",
    "cancel", "boxed", "mathop", "mathrel", "mathbin", "mathord", "underline"
)

// Commands followed by a {...} that we throw away.
private val DISCARD_ARG = setOf("label", "tag", "hspace", "vspace", "phantom", "hphantom", "vphantom")

// Marks put on a single character: \hat{x} -> x + a combining mark.
private val ACCENTS = mapOf(
    "hat" to "\u0302", "widehat" to "\u0302", "bar" to "\u0304",
    "overline" to "\u0305", "vec" to "\u20D7", "overrightarrow" to "\u20D7",
    "tilde" to "\u0303", "widetilde" to "\u0303", "dot" to "\u0307",
    "ddot" to "\u0308"
)

// \mathbb{R} and friends.
private val DOUBLE_STRUCK = mapOf(
    'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ", 'H' to "ℍ"
)

// \begin{pmatrix} ... \end{pmatrix}: the brackets we draw (rows and columns stay simple).
private val ENV_OPEN = mapOf(
    "pmatrix" to "(", "bmatrix" to "[", "Bmatrix" to "{", "vmatrix" to "|",
    "Vmatrix" to "‖", "cases" to "{"
)
private val ENV_CLOSE = mapOf(
    "pmatrix" to ")", "bmatrix" to "]", "Bmatrix" to "}", "vmatrix" to "|",
    "Vmatrix" to "‖", "cases" to ""
)

// Environments that are followed by a COLUMN CODE like {cc} or {|l|r|}.
// The code describes the columns, it is not content, so beginEnvironment skips it.
private val ENV_WITH_ARGUMENT = setOf("array", "tabular", "tabular*", "longtable")

// Spaces (Unicode space characters of different widths).
private const val THIN_SPACE = "\u2009"       // around + - × ...
private const val RELATION_SPACE = "\u2005"   // around = < > ≤ → ...

// If a fraction part contains one of these (or a space), it gets brackets.
private const val COMPLEX_CHARS = "+−±∓=<>≤≥≠≈·×÷/⋯,∑∏∫"

// ============================================================================
//  3. THE PARSER
// ============================================================================

/**
 * Reads LaTeX text from left to right and builds the styled result.
 *
 * @param src          the LaTeX (without $ signs)
 * @param forceUpright true = letters are NOT italic (inside \mathrm, \text...)
 * @param scriptDepth  > 0 = we are inside a small raised/lowered part (no
 *                     extra spaces there)
 * @param level        how deep we are inside \frac{ \frac{..} ..} (limit)
 */
private class MathParser(
    private val src: String,
    private var forceUpright: Boolean = false,
    private var scriptDepth: Int = 0,
    private val level: Int = 0
) {
    private val len = src.length
    private var pos = 0
    private val out = AnnotatedString.Builder()

    // true = the last thing written is a "value" (letter, digit, ")" ...).
    // It tells a minus SIGN ("-1") from a minus BETWEEN two things ("a - b").
    private var prevOperand = false

    // Open \begin{...} environments: the closing bracket each one needs.
    private val envStack = ArrayList<String>()

    // How many { } groups are open right now (limit).
    private var nesting = 0

    private val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    private val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)

    /** Parses everything and returns the result. */
    fun parse(): AnnotatedString {
        if (level > MAX_LEVEL) return AnnotatedString(src)
        parseSeq(false)
        // Close environments that were never closed (unfinished text).
        while (envStack.isNotEmpty()) out.append(envStack.removeAt(envStack.size - 1))
        return out.toAnnotatedString()
    }

    // ---- the main loop -----------------------------------------------------

    /**
     * Parses things one after the other. If [inGroup] is true we stop in
     * front of the "}" that closes the group (the caller eats it).
     */
    private fun parseSeq(inGroup: Boolean) {
        if (nesting >= MAX_NESTING) {
            // Too deep: write the rest as it is and stop.
            out.append(src.substring(pos))
            pos = len
            return
        }
        nesting++
        while (pos < len) {
            if (inGroup && src[pos] == '}') break
            val before = pos
            parseOne()
            if (pos == before) pos++        // safety net: always move forward
        }
        nesting--
    }

    /** Parses ONE thing: a character, a {group}, a \command, a ^ or _ part. */
    private fun parseOne() {
        if (pos >= len) return
        val c = src[pos]
        when {
            c.isWhitespace() -> pos++                 // TeX ignores spaces in formulas
            c == '{' -> {
                pos++
                parseSeq(true)
                if (pos < len && src[pos] == '}') pos++
            }
            c == '}' -> pos++                         // stray closing brace: skip
            c == '\\' -> parseCommand()
            c == '^' -> { pos++; parseScript(true) }
            c == '_' -> { pos++; parseScript(false) }
            c == '-' -> { pos++; appendBinary("−") }
            c == '+' -> { pos++; appendBinary("+") }
            c == '*' -> { pos++; appendBinary("∗") }
            c == '=' || c == '<' || c == '>' -> { pos++; appendRelation(c.toString()) }
            c == '\'' -> { pos++; out.append("′"); prevOperand = true }
            c == ',' -> {
                pos++
                out.append(",")
                if (scriptDepth == 0) out.append(THIN_SPACE)
                prevOperand = false
            }
            c == '(' || c == '[' -> { pos++; out.append(c); prevOperand = false }
            c == ')' || c == ']' -> { pos++; out.append(c); prevOperand = true }
            c == '&' -> { pos++; out.append("\u2003"); prevOperand = false }   // table column gap
            c == '~' -> { pos++; out.append("\u00A0") }                        // fixed space
            c == '$' -> pos++                                                  // stray dollar sign: skip
            c.isLetter() -> { pos++; appendItalic(c.toString()); prevOperand = true }
            else -> { pos++; out.append(c); prevOperand = true }               // digits . ! | ...
        }
    }

    // ---- small writing helpers --------------------------------------------

    private fun skipSpaces() {
        while (pos < len && src[pos].isWhitespace()) pos++
    }

    /** Writes a letter/word in italic (or upright when forceUpright is on). */
    private fun appendItalic(text: String) {
        if (forceUpright) {
            out.append(text)
        } else {
            out.pushStyle(italicStyle)
            out.append(text)
            out.pop()
        }
    }

    /** "=", "<", "≤", "→" ...: a bit of space on both sides. */
    private fun appendRelation(sym: String) {
        if (scriptDepth == 0) {
            out.append(RELATION_SPACE); out.append(sym); out.append(RELATION_SPACE)
        } else {
            out.append(sym)
        }
        prevOperand = false
    }

    /** "+", "−", "×", "·": space on both sides, but NOT when it is a sign ("−1"). */
    private fun appendBinary(sym: String) {
        if (scriptDepth == 0 && prevOperand) {
            out.append(THIN_SPACE); out.append(sym); out.append(THIN_SPACE)
        } else {
            out.append(sym)
        }
        prevOperand = false
    }

    /** "⋯", "…": space on both sides. */
    private fun appendEllipsis(sym: String) {
        if (scriptDepth == 0) {
            out.append(THIN_SPACE); out.append(sym); out.append(THIN_SPACE)
        } else {
            out.append(sym)
        }
        prevOperand = false
    }

    // ---- ^ and _  (raised and lowered text) --------------------------------

    /**
     * Reads the ONE thing after ^ or _ and writes it small and raised
     * (sup = true) or lowered (sup = false).
     * x^2 -> 2 raised.   x^{n+1} -> n+1 raised.   x_i -> i lowered.
     * Example from your chat:  \text{SO}_4^{2-}  ->  SO  ₄  ²⁻
     */
    private fun parseScript(sup: Boolean) {
        skipSpaces()
        if (pos >= len) return
        out.pushStyle(
            SpanStyle(
                baselineShift = if (sup) BaselineShift.Superscript else BaselineShift.Subscript,
                fontSize = 0.75.em
            )
        )
        scriptDepth++
        val before = pos
        parseOne()                       // a {group}, a \command or one character
        if (pos == before) pos++
        scriptDepth--
        out.pop()
        prevOperand = true               // the script belongs to its base: still a "value"
    }

    // ---- argument readers ---------------------------------------------------

    /**
     * Reads the content of the {...} that starts at pos (the braces are
     * removed, nested braces are counted) and moves behind the "}".
     */
    private fun readBraceContent(): String {
        pos++                                   // skip "{"
        val start = pos
        var depth = 1
        while (pos < len) {
            val c = src[pos]
            if (c == '\\') { pos += 2; continue }     // \{ and \} are not real braces
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) {
                    val content = src.substring(start, pos)
                    pos++                              // skip "}"
                    return content
                }
            }
            pos++
        }
        // No closing brace (unfinished text): take the rest.
        return src.substring(start, minOf(pos, len))
    }

    /**
     * Reads one ARGUMENT of a command and returns its LaTeX text:
     * a {group}, a \command, or a single character.
     */
    private fun readArgSource(): String {
        skipSpaces()
        if (pos >= len) return ""
        val c = src[pos]
        if (c == '{') return readBraceContent()
        if (c == '\\') {
            val start = pos
            pos++
            if (pos < len && src[pos].isLetter()) {
                while (pos < len && src[pos].isLetter()) pos++
            } else if (pos < len) {
                pos++
            }
            return src.substring(start, pos)
        }
        pos++
        return c.toString()
    }

    /** Converts a piece of LaTeX with a fresh parser (used for fraction parts...). */
    private fun renderSub(source: String): AnnotatedString {
        if (level >= MAX_LEVEL) return AnnotatedString(source)
        return MathParser(source, forceUpright, scriptDepth, level + 1).parse()
    }

    /** Writes [part], with brackets around it when it is more than one simple thing. */
    private fun appendWrapped(part: AnnotatedString) {
        val t = part.text
        val simple = t.length <= 1 ||
                (t.startsWith("(") && t.endsWith(")")) ||
                t.none { it.isWhitespace() || COMPLEX_CHARS.indexOf(it) >= 0 }
        if (simple) {
            out.append(part)
        } else {
            out.append("(")
            out.append(part)
            out.append(")")
        }
    }

    // ---- \commands -----------------------------------------------------------

    private fun parseCommand() {
        pos++                                       // skip the backslash
        if (pos >= len) {
            out.append("\\")
            return
        }
        val c = src[pos]

        // A command made of ONE non-letter: \, \; \\ \{ \% ...
        if (!c.isLetter()) {
            pos++
            symbolCommand(c)
            return
        }

        // A command made of letters: \alpha \frac ...
        val start = pos
        while (pos < len && src[pos].isLetter()) pos++
        wordCommand(src.substring(start, pos))
    }

    /** \, \; \: \! \\ \{ \} \% \$ \& \# \_ \| and friends. */
    private fun symbolCommand(c: Char) {
        when (c) {
            ',' -> out.append(THIN_SPACE)
            ';', ':' -> out.append(RELATION_SPACE)
            '!' -> { /* negative space: nothing */ }
            ' ' -> out.append(" ")
            '\\' -> { out.append("\n"); prevOperand = false }     // new row
            '|' -> { out.append("‖"); prevOperand = true }
            else -> { out.append(c); prevOperand = true }          // \{ \} \% \$ \& \# \_
        }
    }

    /** A command with a name, like \alpha, \frac, \text. */
    private fun wordCommand(name: String) {
        // ---- the symbol tables -------------------------------------------
        val greek = GREEK_LOWER[name]
        if (greek != null) { appendItalic(greek); prevOperand = true; return }

        val ordinary = ORDINARY[name]
        if (ordinary != null) { out.append(ordinary); prevOperand = true; return }

        val ellipsis = ELLIPSIS[name]
        if (ellipsis != null) { appendEllipsis(ellipsis); return }

        val binary = BINARY[name]
        if (binary != null) { appendBinary(binary); return }

        val relation = RELATIONS[name]
        if (relation != null) { appendRelation(relation); return }

        val big = BIG[name]
        if (big != null) { out.append(big); prevOperand = false; return }

        if (name in FUNCTIONS) { appendFunction(name); return }
        if (name in IGNORED) return

        if (name in DISCARD_ARG) {
            readArgSource()
            return
        }

        val accent = ACCENTS[name]
        if (accent != null) { appendAccent(name, accent); return }

        // ---- commands with arguments ---------------------------------------
        when (name) {
            "frac", "dfrac", "tfrac", "cfrac" -> appendFraction()
            "binom", "dbinom", "tbinom" -> appendBinomial()
            "sqrt" -> appendSqrt()
            "text", "textrm", "mbox", "hbox", "textnormal" -> appendPlainText()
            "mathrm", "operatorname", "mathsf", "mathtt" -> parseStyledArg(upright = true, bold = false)
            "mathbf", "textbf", "boldsymbol", "bm" -> parseStyledArg(upright = true, bold = true)
            "mathit", "textit", "emph", "mathcal", "mathscr", "mathfrak" ->
                parseStyledArg(upright = false, bold = false)
            "mathbb" -> appendDoubleStruck()
            "left", "right" -> {
                // \left( ... \right)  : the brackets are written normally.
                // "\left." means "no bracket": skip the dot.
                skipSpaces()
                if (pos < len && src[pos] == '.') pos++
            }
            "quad" -> out.append("\u2003")
            "qquad" -> out.append("\u2003\u2003")
            "bmod" -> { out.append(" mod "); prevOperand = false }
            "pmod" -> {
                val arg = renderSub(readArgSource())
                out.append(" (mod ")
                out.append(arg)
                out.append(")")
                prevOperand = true
            }
            "begin" -> beginEnvironment()
            "end" -> endEnvironment()
            else -> {
                // Unknown command: show its name as plain text, never garbage.
                out.append(name)
                prevOperand = true
            }
        }
    }

    /** sin, cos, log, lim ... : upright name, small space after (not before ^ or _). */
    private fun appendFunction(name: String) {
        out.append(name)
        skipSpaces()
        if (pos < len && src[pos] != '^' && src[pos] != '_') out.append(THIN_SPACE)
        prevOperand = false
    }

    /** \frac{a}{b}  ->  a/b   (brackets when a part is longer than one simple thing). */
    private fun appendFraction() {
        val top = renderSub(readArgSource())
        val bottom = renderSub(readArgSource())
        appendWrapped(top)
        out.append("/")
        appendWrapped(bottom)
        prevOperand = true
    }

    /** \binom{m}{k}  ->  C(m, k). */
    private fun appendBinomial() {
        val top = renderSub(readArgSource())
        val bottom = renderSub(readArgSource())
        out.append("C(")
        out.append(top)
        out.append(", ")
        out.append(bottom)
        out.append(")")
        prevOperand = true
    }

    /** \sqrt{x} -> √x ,  \sqrt{x+1} -> √(x + 1) ,  \sqrt[3]{x} -> small 3 then √x. */
    private fun appendSqrt() {
        skipSpaces()
        // Optional root number in [ ].
        if (pos < len && src[pos] == '[') {
            val close = src.indexOf(']', pos)
            if (close > pos) {
                val index = renderSub(src.substring(pos + 1, close))
                pos = close + 1
                out.pushStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em))
                out.append(index)
                out.pop()
            }
        }
        val inside = renderSub(readArgSource())
        out.append("√")
        appendWrapped(inside)
        prevOperand = true
    }

    /** \text{any words} : written exactly as given (spaces kept), not italic. */
    private fun appendPlainText() {
        skipSpaces()
        if (pos >= len) return
        val text = if (src[pos] == '{') readBraceContent() else src[pos++].toString()
        out.append(text)
        prevOperand = true
    }

    /** \mathrm{..}, \mathbf{..}, \mathit{..}: reads ONE argument with a changed look. */
    private fun parseStyledArg(upright: Boolean, bold: Boolean) {
        val savedUpright = forceUpright
        forceUpright = upright
        if (bold) out.pushStyle(boldStyle)
        skipSpaces()
        if (pos < len) {
            val before = pos
            parseOne()
            if (pos == before) pos++
        }
        if (bold) out.pop()
        forceUpright = savedUpright
        prevOperand = true
    }

    /** \mathbb{R} -> ℝ. Letters without a special sign stay as they are. */
    private fun appendDoubleStruck() {
        val raw = readArgSource()
        val sb = StringBuilder()
        for (ch in raw) sb.append(DOUBLE_STRUCK[ch] ?: ch.toString())
        out.append(sb.toString())
        prevOperand = true
    }

    /** \hat{x}, \bar{x}, \vec{v}: a mark on ONE character. */
    private fun appendAccent(name: String, mark: String) {
        val inside = renderSub(readArgSource())
        out.append(inside)
        // A combining mark only looks right on one character.
        if (inside.text.length == 1) out.append(mark)
        prevOperand = true
    }

    /** \begin{pmatrix} ... : we write the brackets; rows are lines, columns are gaps. */
    private fun beginEnvironment() {
        val env = readArgSource()

        // \begin{array}{cc} ... : the {cc} that follows is the COLUMN CODE
        // (how to align each column), not content. Skip it, together with an
        // optional position like [t], so it is not printed as stray text.
        if (env in ENV_WITH_ARGUMENT) {
            skipSpaces()
            if (pos < len && src[pos] == '[') {
                val close = src.indexOf(']', pos)
                if (close > pos) pos = close + 1
            }
            skipSpaces()
            if (pos < len && src[pos] == '{') readBraceContent()
        }

        out.append(ENV_OPEN[env] ?: "")
        envStack.add(ENV_CLOSE[env] ?: "")
        prevOperand = false
    }

    private fun endEnvironment() {
        readArgSource()
        if (envStack.isNotEmpty()) out.append(envStack.removeAt(envStack.size - 1))
        prevOperand = true
    }
}
