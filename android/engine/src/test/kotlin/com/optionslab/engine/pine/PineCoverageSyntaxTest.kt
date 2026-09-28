package com.optionslab.engine.pine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lexer and parser: every token kind, every syntax path and every syntax error. */
class PineCoverageSyntaxTest {
    private val H = "indicator(\"x\")\n"

    private fun syntaxError(src: String, needle: String, line: Int? = null, col: Int? = null) {
        val e = PC.failed(src).errors
        assertEquals(1, e.size, e.toString())
        assertTrue(e[0].message.contains(needle), "wanted '$needle', got ${e[0]}")
        line?.let { assertEquals(it, e[0].line, e.toString()) }
        col?.let { assertEquals(it, e[0].col, e.toString()) }
    }

    @Test fun everyHiddenCharacterIsRefusedWithItsCodeAndPosition() {
        for (c in listOf('‪', '‬', '‮', '⁦', '⁩', '‎', '‏', '؜', '​', '‍', '⁠', '﻿', '­')) {
            val e = PC.failed("${H}x = 1$c\nplot(x)").errors.single()
            assertEquals(2, e.line); assertEquals(6, e.col)
            assertTrue(e.message.contains("U+%04X".format(c.code)), e.message)
        }
        // Ordinary non-ASCII text in a string is fine.
        PC.ok("${H}plot(close, \"Préço ₹\")")
    }

    @Test fun numbersInEveryForm() {
        assertEquals(1000.0, PC.value("1_000"))
        assertEquals(0.5, PC.value(".5"))
        assertEquals(1e5, PC.value("1e5"))
        assertEquals(2.5e-3, PC.value("2.5E-3"))
        assertEquals(300.0, PC.value("3e+2"))
        syntaxError("${H}x = 1.2.3", "Bad number '1.2.3'", 2, 5)
        syntaxError("${H}x = 1e", "Bad number '1e'")
    }

    @Test fun stringsWithEscapesAndBothQuotes() {
        val s = PC.ok("${H}plotshape(true, title = 'it\\'s', text = \"a\\nb\\tc\\\\d\\qe\")")
        assertEquals("it's", s.shapes[0].title)
        assertEquals("a\nb\tc\\dqe", s.shapes[0].text)
        // A backslash as the last character does not escape the end of the line.
        syntaxError("${H}x = \"abc\\", "never closed", 2, 5)
    }

    @Test fun coloursAndTheirErrors() {
        val s = PC.ok("${H}plot(close, color = #ff00aa)\nplot(close, color = #11223344)")
        assertEquals("#FF00AA", s.plots[0].color)
        assertEquals("#112233", s.plots[1].color)       // the alpha is dropped for the chart
        syntaxError("${H}plot(close, color = #12345)", "Bad colour '#12345'")
        syntaxError("${H}plot(close, color = #GG0000)", "Bad colour '#GG0000'")
    }

    @Test fun identifiersWithUnderscoresAndDots() {
        assertEquals(3.0, PC.value("_a + my_var.b", pre = "_a = 1\nmy_var.b = 2"))
        // A dot followed by a digit ends the name (it is not part of a member).
        syntaxError("${H}x = close.5", "one statement per line")
    }

    @Test fun unexpectedCharacters() {
        syntaxError("${H}x = 1 @ 2", "Unexpected character '@'", 2, 7)
        syntaxError("${H}x = 1 & 2", "Unexpected character '&'")
    }

    @Test fun indentationRules() {
        // A line indented by a non-multiple of 4 continues the one above.
        assertEquals(3.0, PC.value("x", pre = "x = 1\n  + 2"))
        // Tabs count as 4 spaces.
        assertEquals(2.0, PC.value("x", pre = "x = 1\nif true\n\tx := 2"))
        syntaxError("  x = 1\nindicator(\"x\")", "multiple of 4", 1, 1)
        syntaxError("${H}if true\n        x = 1", "Indented too far", 3)
        syntaxError("    x = 1\n$H", "Unexpected indentation", 1)
        // Blank lines and comment lines do not change the indentation.
        assertEquals(5.0, PC.value("x", pre = "x = 1\nif true\n\n        // comment\n    x := 5"))
    }

    @Test fun wrappedLineAfterADanglingOperatorMayBeIndentedByTwo() {
        assertEquals(3.0, PC.value("x", pre = "x = 1 +\n  2"))
    }

    @Test fun linesEndingInAnOperatorCarryOn() {
        for ((op, want) in listOf("+" to 5.0, "-" to 1.0, "*" to 6.0, "/" to 1.5, "%" to 1.0)) assertEquals(want, PC.value("x", pre = "x = 3 $op\n2"), op)
        assertEquals(1.0, PC.value("x", pre = "x = true ?\n1 :\n2"))
        for ((op, want) in listOf("==" to 0.0, "!=" to 1.0, "<" to 0.0, ">" to 1.0, "<=" to 0.0, ">=" to 1.0, "and" to 1.0, "or" to 1.0))
            assertEquals(want, PC.value("x ? 1 : 0", pre = "x = 3 $op\n2"), op)
        assertEquals(3.0, PC.value("math.max(1,\n2,\n3)"))
        // A dangling operator on the last line still ends the script cleanly (and then fails to parse).
        syntaxError("${H}x = 1 +", "Expected a value but found end of line")
    }

    @Test fun bracketsLeftOpen() {
        syntaxError("${H}plot(close", "A bracket is opened but never closed", 2)
        syntaxError("${H}x = [1, 2", "never closed")
        // A stray closing bracket does not make the depth negative.
        syntaxError("${H}x = 1)", "one statement per line")
    }

    @Test fun describedTokensInErrors() {
        syntaxError("${H}x = ", "found end of line")
        syntaxError("${H}plot(\"a\" \"b\")", "found \"b\"")
        syntaxError("${H}x = )", "Expected a value but found ')'")
        syntaxError("${H}plot(close 1)", "Expected ',' or ')' in the call to plot(), found '1'")
        syntaxError("${H}for 1 = 0 to 2\n    x = 1", "Expected the loop variable but found '1'")
        syntaxError("${H}for i 0 to 2\n    x = 1", "Expected '=' but found '0'")
        syntaxError("${H}if true\nx = 1", "Expected an indented block (4 spaces) here")
        syntaxError("${H}if close > open plot(close)", "Expected a new line before the block, found 'plot'")
        syntaxError("${H}x = 1 y = 2", "Unexpected 'y': one statement per line")
    }

    @Test fun unsupportedKeywords() {
        for (k in listOf("import", "export", "method", "type")) syntaxError("${H}$k foo", "'$k' is not supported on the phone", 2, 1)
    }

    @Test fun forLoopForms() {
        assertEquals(20.0, PC.value("s", pre = "s = 0\nfor i = 0 to 8 by 2\n    s += i"))
        assertEquals(6.0, PC.value("s", pre = "s = 0\nfor i = 3 to 1\n    s += i"))          // counts down on its own
        assertEquals(3.0, PC.value("s", pre = "a = array.from(1, 2)\ns = 0\nfor x in a\n    s += x"))
        assertEquals(1.0, PC.value("s", pre = "a = array.from(5, 7)\ns = 0\nfor [i, x] in a\n    s += i"))
        syntaxError("${H}a = array.from(1)\nfor [i, x] of a\n    y = 1", "Expected 'in'")
        syntaxError("${H}for [1, x] in a\n    y = 1", "Expected the index variable")
        syntaxError("${H}for [i 2] in a\n    y = 1", "Expected ','")
        syntaxError("${H}for [i, 2] in a\n    y = 1", "Expected the item variable")
        syntaxError("${H}for [i, x 3] in a\n    y = 1", "Expected ']'")
    }

    @Test fun tupleDeclarationsAndTupleLookalikes() {
        assertEquals(12.0, PC.value("a + b", pre = "f() => [5, 7]\n[a, b] = f()"))
        // Not tuple declarations: expressions that start with '[' (a non-name, no '=', or a missing comma).
        PC.ok("${H}x = 1\n[x, 1]\n[x]\nplot(x)")
        syntaxError("${H}x = 1\n[x x] = 2", "Expected ',' or ']' in the list, found 'x'")
    }

    @Test fun functionDefinitions() {
        assertEquals(7.0, PC.value("f(3)", pre = "f(x, y = 4) => x + y"))
        assertEquals(10.0, PC.value("g(2)", pre = "g(float x) =>\n    y = x * 5\n    y"))
        syntaxError("${H}f(a b) => a", "Expected ',' or ')' in the parameter list")
        syntaxError("${H}f(1) => 2", "Expected a parameter name but found '1'")
        // An unclosed parameter list is not taken for a function (and fails as a bracket).
        syntaxError("${H}f(a => a", "never closed")
    }

    @Test fun typedDeclarations() {
        val src = """
            indicator("t")
            float x = 1.5
            series int n = 2
            simple float s = 0.5
            const string lbl = "k"
            float[] a = array.new_float(2, 3)
            array<int> b = array.from(4, 5)
            var int k = 7
            varip bool flag = true
            plot(x + n + s + array.sum(a) + array.sum(b) + k + (flag ? 100 : 0) + (lbl == "k" ? 1000 : 0))
        """.trimIndent()
        assertEquals(1.5 + 2 + 0.5 + 6 + 9 + 7 + 100 + 1000, PC.plot(src, PC.bars(1.0))[0])
        // A type word followed by something other than a declaration is an ordinary expression.
        syntaxError("${H}float(1) = 2", "Cannot assign to this")
        syntaxError("${H}var 1 = 2", "Expected a variable name after 'var'")
        // A bare type name used as a variable still works.
        assertEquals(3.0, PC.value("int", pre = "int = 3"))
    }

    @Test fun assignmentsAndTheirOperators() {
        assertEquals(1.0, PC.value("x", pre = "x = 10\nx -= 1\nx *= 2\nx /= 3\nx %= 5"))
        syntaxError("${H}close[1] = 2", "Cannot assign to this; declare a variable with 'name = value'")
    }

    @Test fun switchSyntax() {
        syntaxError("${H}x = switch close 1\n    1 => 2", "Expected a new line after the switch")
        syntaxError("${H}x = switch close\n1 => 2", "Expected the switch cases, indented by 4 spaces")
        syntaxError("${H}x = switch\n    => 1\n    => 2", "A switch can have only one default (=>)", 4)
        syntaxError("${H}x = switch\n    true 1", "Expected '=>'")
        // Block bodies in a switch.
        assertEquals(9.0, PC.value("x", pre = "x = switch\n    close > 100 =>\n        1\n    =>\n        y = 4\n        y + 5"))
    }

    @Test fun switchNestingIsLimitedLikeBlocks() {
        val sb = StringBuilder(H)
        for (i in 0 until 21) sb.append("    ".repeat(2 * i)).append("switch\n").append("    ".repeat(2 * i + 1)).append("true =>\n")
        sb.append("    ".repeat(42)).append("x = 1\n")
        assertTrue(PC.errors(sb.toString()).any { it.contains("Blocks are nested too deeply (over 40 levels)") })
    }

    @Test fun ifElseChainsAsStatementsAndValues() {
        val pre = """
            x = if close > 5
                1
            else if close > 2
                2
            else
                3
            y = 0
            if close > 5
                y := 10
            else if close > 2
                y := 20
            else
                y := 30
        """.trimIndent()
        assertEquals(listOf(3.0, 2.0, 1.0), PC.plot("${H}$pre\nplot(x)", PC.bars(1.0, 3.0, 6.0)).toList())
        assertEquals(listOf(30.0, 20.0, 10.0), PC.plot("${H}$pre\nplot(y)", PC.bars(1.0, 3.0, 6.0)).toList())
    }

    @Test fun comparisonOperators() {
        for ((op, want) in listOf("<" to 1.0, ">" to 0.0, "<=" to 1.0, ">=" to 0.0, "==" to 0.0, "!=" to 1.0))
            assertEquals(want, PC.value("(1 $op 2) ? 1 : 0"), op)
        assertEquals(1.0, PC.value("(2 <= 2 and 2 >= 2) ? 1 : 0"))
    }

    @Test fun genericsOnlyOnArrays() {
        assertEquals(2.0, PC.value("array.size(array.new<float>(2))"))
        syntaxError("${H}x = foo<int>(1)", "Generic types are only supported for arrays")
        // a < b > c is still a comparison when no '(' follows.
        assertEquals(0.0, PC.value("(a < b > c) ? 1 : 0", pre = "a = 1\nb = 2\nc = 3"))
    }

    @Test fun callArguments() {
        syntaxError("${H}plot(title = \"a\", close)", "A positional argument cannot follow a named one")
        assertEquals(0.0, PC.value("array.size(array.from())"))
    }

    @Test fun listLiterals() {
        assertEquals(3.0, PC.value("b", pre = "[a, b] = [1, 3]"))
        syntaxError("${H}[a, b] = [1 2]", "Expected ',' or ']' in the list, found '2'")
        syntaxError("${H}x = [1, 2", "never closed")
    }

    @Test fun expressionDepthLimits() {
        syntaxError("${H}x = " + "(".repeat(125) + "1" + ")".repeat(125), "Nested too deeply")
        syntaxError("${H}x = " + List(125) { "1" }.joinToString(" + "), "too long or nested too deeply")
        // Just under the limit is fine.
        assertEquals(100.0, PC.value(List(100) { "1" }.joinToString(" + ")))
    }

    @Test fun ternaryAndNotNestParse() {
        assertEquals(2.0, PC.value("false ? 1 : true ? 2 : 3"))
        assertEquals(1.0, PC.value("not not true ? 1 : 0"))
        assertEquals(-3.0, PC.value("- + 3"))
    }

    @Test fun breakAndContinueParse() {
        assertEquals(3.0, PC.value("s", pre = "s = 0\nfor i = 1 to 10\n    if i == 2\n        continue\n    if i > 2\n        break\n    s += i + 2"))
    }
}
