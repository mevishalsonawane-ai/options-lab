package com.optionslab.engine.pine

/**
 * Pine Script (v5 subset): tokens, the syntax tree and the parser.
 *
 * The parser turns source text into statements, or stops at the first syntax error with
 * its line and column. Blocks are indented by 4 spaces (or a tab), as in TradingView;
 * a line indented by a non-multiple of 4 continues the line above it, and so does any
 * line inside open brackets.
 */
class PineError(val line: Int, val col: Int, message: String) : Exception(message)

internal enum class T { NUM, STR, ID, COLOR, OP, NL, INDENT, DEDENT, EOF }

internal data class Tok(val t: T, val text: String, val line: Int, val col: Int, val num: Double = 0.0)

internal object Lexer {
    private val KEYWORDS = setOf("and", "or", "not", "if", "else", "for", "to", "by", "var", "varip", "true", "false",
        "while", "break", "continue", "switch", "import", "export", "method", "type")
    private val OPS3 = listOf("...")
    private val OPS2 = listOf(":=", "==", "!=", "<=", ">=", "=>", "+=", "-=", "*=", "/=", "%=")
    private const val OPS1 = "+-*/%<>=?:()[],"

    fun lex(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        val indents = ArrayDeque<Int>().apply { addLast(0) }
        var depth = 0                        // open ( and [
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for ((li, raw) in lines.withIndex()) {
            val lineNo = li + 1
            // Indentation: tabs count as 4.
            var width = 0; var p = 0
            while (p < raw.length && (raw[p] == ' ' || raw[p] == '\t')) { width += if (raw[p] == '\t') 4 else 1; p++ }
            val body = raw.substring(p)
            if (body.isEmpty() || body.startsWith("//")) continue
            val continues = depth > 0 || (width % 4 != 0 && width > indents.last() && out.isNotEmpty() && out.last().t == T.NL)
            if (continues) {
                if (depth == 0 && out.isNotEmpty() && out.last().t == T.NL) out.removeAt(out.size - 1)
            } else {
                if (width % 4 != 0) throw PineError(lineNo, 1, "Indentation must be a multiple of 4 spaces (or tabs)")
                if (width > indents.last()) {
                    if (width != indents.last() + 4) throw PineError(lineNo, 1, "Indented too far: a block goes in by exactly 4 spaces")
                    indents.addLast(width); out += Tok(T.INDENT, "", lineNo, 1)
                } else {
                    while (width < indents.last()) { indents.removeLast(); out += Tok(T.DEDENT, "", lineNo, 1) }
                    if (width != indents.last()) throw PineError(lineNo, 1, "This line's indentation does not match any block above it")
                }
            }
            var i = p
            while (i < raw.length) {
                val c = raw[i]
                val col = i + 1
                when {
                    c == ' ' || c == '\t' -> i++
                    c == '/' && i + 1 < raw.length && raw[i + 1] == '/' -> i = raw.length
                    c.isDigit() || (c == '.' && i + 1 < raw.length && raw[i + 1].isDigit()) -> {
                        var j = i
                        while (j < raw.length && (raw[j].isDigit() || raw[j] == '.' || raw[j] == '_')) j++
                        if (j < raw.length && (raw[j] == 'e' || raw[j] == 'E')) {
                            j++
                            if (j < raw.length && (raw[j] == '+' || raw[j] == '-')) j++
                            while (j < raw.length && raw[j].isDigit()) j++
                        }
                        val text = raw.substring(i, j).replace("_", "")
                        val v = text.toDoubleOrNull() ?: throw PineError(lineNo, col, "Bad number '$text'")
                        out += Tok(T.NUM, text, lineNo, col, v); i = j
                    }
                    c == '"' || c == '\'' -> {
                        val sb = StringBuilder(); var j = i + 1
                        while (j < raw.length && raw[j] != c) {
                            if (raw[j] == '\\' && j + 1 < raw.length) {
                                j++
                                sb.append(when (raw[j]) { 'n' -> '\n'; 't' -> '\t'; else -> raw[j] })
                            } else sb.append(raw[j])
                            j++
                        }
                        if (j >= raw.length) throw PineError(lineNo, col, "This string is never closed")
                        out += Tok(T.STR, sb.toString(), lineNo, col); i = j + 1
                    }
                    c == '#' -> {
                        var j = i + 1
                        while (j < raw.length && raw[j].isLetterOrDigit()) j++
                        val hex = raw.substring(i + 1, j)
                        if ((hex.length != 6 && hex.length != 8) || hex.any { it.lowercaseChar() !in "0123456789abcdef" })
                            throw PineError(lineNo, col, "Bad colour '#$hex': use #RRGGBB or #RRGGBBAA")
                        out += Tok(T.COLOR, "#" + hex.uppercase(), lineNo, col); i = j
                    }
                    c.isLetter() || c == '_' -> {
                        var j = i
                        while (j < raw.length && (raw[j].isLetterOrDigit() || raw[j] == '_' ||
                                (raw[j] == '.' && j + 1 < raw.length && (raw[j + 1].isLetter() || raw[j + 1] == '_')))) j++
                        out += Tok(T.ID, raw.substring(i, j), lineNo, col); i = j
                    }
                    else -> {
                        val three = OPS3.firstOrNull { raw.startsWith(it, i) }
                        val two = OPS2.firstOrNull { raw.startsWith(it, i) }
                        val op = three ?: two ?: if (c in OPS1) c.toString() else throw PineError(lineNo, col, "Unexpected character '$c'")
                        if (op == "(" || op == "[") depth++
                        if (op == ")" || op == "]") depth = maxOf(0, depth - 1)
                        out += Tok(T.OP, op, lineNo, col); i += op.length
                    }
                }
            }
            if (depth == 0) {
                // A line that ends in an operator or comma carries on to the next.
                val last = out.lastOrNull()
                val dangling = last != null && (last.t == T.OP || last.t == T.ID) && last.text in setOf("+", "-", "*", "/", "%", "?", ":", ",", "and", "or", "==", "!=", "<", ">", "<=", ">=")
                if (!dangling) out += Tok(T.NL, "", lineNo, raw.length + 1)
            }
        }
        val endLine = lines.size + 1
        if (depth > 0) throw PineError(lines.size, 1, "A bracket is opened but never closed")
        if (out.isNotEmpty() && out.last().t != T.NL && out.last().t != T.DEDENT) out += Tok(T.NL, "", endLine, 1)
        while (indents.size > 1) { indents.removeLast(); out += Tok(T.DEDENT, "", endLine, 1) }
        out += Tok(T.EOF, "", endLine, 1)
        return out.map { if (it.t == T.ID && it.text in KEYWORDS) it.copy(t = T.OP) else it }
    }
}

// ---- syntax tree -------------------------------------------------------------------------

internal sealed class Expr { abstract val line: Int; abstract val col: Int; var id = 0 }
internal data class Num(val v: Double, override val line: Int, override val col: Int) : Expr()
internal data class Str(val v: String, override val line: Int, override val col: Int) : Expr()
internal data class Bool(val v: Boolean, override val line: Int, override val col: Int) : Expr()
internal data class ColorLit(val v: String, override val line: Int, override val col: Int) : Expr()
internal data class Name(val name: String, override val line: Int, override val col: Int) : Expr()
internal data class Arg(val name: String?, val value: Expr)
internal data class Call(val name: String, val args: List<Arg>, override val line: Int, override val col: Int) : Expr()
internal data class Index(val target: Expr, val offset: Expr, override val line: Int, override val col: Int) : Expr()
internal data class Unary(val op: String, val e: Expr, override val line: Int, override val col: Int) : Expr()
internal data class Binary(val op: String, val a: Expr, val b: Expr, override val line: Int, override val col: Int) : Expr()
internal data class Ternary(val c: Expr, val a: Expr, val b: Expr, override val line: Int, override val col: Int) : Expr()
internal data class TupleLit(val items: List<Expr>, override val line: Int, override val col: Int) : Expr()
internal data class IfExpr(val stmt: If, override val line: Int, override val col: Int) : Expr()

internal sealed class Stmt { abstract val line: Int; abstract val col: Int }
internal data class Decl(val name: String, val value: Expr, val persistent: Boolean, override val line: Int, override val col: Int) : Stmt()
internal data class TupleDecl(val names: List<String>, val value: Expr, override val line: Int, override val col: Int) : Stmt()
internal data class Assign(val name: String, val op: String, val value: Expr, override val line: Int, override val col: Int) : Stmt()
internal data class If(val cond: Expr, val then: List<Stmt>, val orElse: List<Stmt>?, override val line: Int, override val col: Int) : Stmt()
internal data class For(val v: String, val from: Expr, val to: Expr, val by: Expr?, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt()
internal data class While(val cond: Expr, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt()
internal data class Jump(val brk: Boolean, override val line: Int, override val col: Int) : Stmt()
internal data class ExprStmt(val e: Expr, override val line: Int, override val col: Int) : Stmt()
internal data class FuncDef(val name: String, val params: List<Pair<String, Expr?>>, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt()

internal class Parser(private val toks: List<Tok>) {
    private var p = 0
    private var ids = 0
    private val typeWords = setOf("int", "float", "bool", "string", "color", "series", "simple", "const", "line", "label", "box", "table")

    private fun peek(k: Int = 0) = toks[minOf(p + k, toks.size - 1)]
    private fun at(text: String) = peek().t == T.OP && peek().text == text
    private fun atK(k: Int, text: String) = peek(k).t == T.OP && peek(k).text == text
    private fun next() = toks[p++]
    private fun err(t: Tok, msg: String): Nothing = throw PineError(t.line, t.col, msg)
    private fun describe(t: Tok) = when (t.t) {
        T.NL -> "end of line"; T.EOF -> "end of script"; T.INDENT -> "an indented block"; T.DEDENT -> "end of block"
        T.STR -> "\"${t.text}\""; else -> "'${t.text}'"
    }
    private fun expect(text: String): Tok {
        if (!at(text)) err(peek(), "Expected '$text' but found ${describe(peek())}")
        return next()
    }
    private fun ident(what: String = "a name"): Tok {
        if (peek().t != T.ID) err(peek(), "Expected $what but found ${describe(peek())}")
        return next()
    }
    private fun <E : Expr> E.tag(): E { id = ++ids; return this }

    fun program(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (peek().t != T.EOF) {
            if (peek().t == T.NL) { next(); continue }
            if (peek().t == T.INDENT) err(peek(), "Unexpected indentation")
            out += statement()
        }
        return out
    }

    private fun block(): List<Stmt> {
        if (peek().t != T.NL) err(peek(), "Expected a new line before the block, found ${describe(peek())}")
        next()
        if (peek().t != T.INDENT) err(peek(), "Expected an indented block (4 spaces) here")
        next()
        val out = ArrayList<Stmt>()
        while (peek().t != T.DEDENT && peek().t != T.EOF) {
            if (peek().t == T.NL) { next(); continue }
            out += statement()
        }
        if (peek().t == T.DEDENT) next()
        if (out.isEmpty()) err(peek(), "Empty block")
        return out
    }

    private fun endOfStatement() {
        if (peek().t == T.NL) { next(); return }
        if (p > 0 && toks[p - 1].t == T.DEDENT) return          // an if block on the right of '=' ended the line
        if (peek().t == T.DEDENT || peek().t == T.EOF) return
        err(peek(), "Unexpected ${describe(peek())}: one statement per line")
    }

    private fun isFuncDef(): Boolean {
        if (peek().t != T.ID || !atK(1, "(")) return false
        var k = 2; var d = 1
        while (d > 0) {
            val t = peek(k)
            if (t.t == T.EOF) return false
            if (t.t == T.OP && t.text == "(") d++
            if (t.t == T.OP && t.text == ")") d--
            k++
        }
        return atK(k, "=>")
    }

    private fun statement(): Stmt {
        val t = peek()
        if (t.t == T.OP) when (t.text) {
            "if" -> return ifStmt()
            "for" -> {
                next()
                val v = ident("the loop variable")
                if (peek().text == "in") err(peek(), "for...in loops are not supported yet")
                expect("=")
                val from = expr(); expect("to"); val to = expr()
                val by = if (at("by")) { next(); expr() } else null
                return For(v.text, from, to, by, block(), t.line, t.col)
            }
            "while" -> { next(); val c = expr(); return While(c, block(), t.line, t.col) }
            "break", "continue" -> { next(); endOfStatement(); return Jump(t.text == "break", t.line, t.col) }
            "var", "varip" -> {
                next()
                skipType()
                val n = ident("a variable name after 'var'")
                expect("=")
                val v = rhs()
                return Decl(n.text, v, true, t.line, t.col).also { endOfStatement() }
            }
            "switch" -> err(t, "'switch' is not supported yet: use if / else if")
            "import", "export", "method", "type" -> err(t, "'${t.text}' is not supported on the phone")
            "[" -> {
                // [a, b, c] = f(...)
                val save = p
                next()
                val names = ArrayList<String>()
                var ok = true
                while (true) {
                    if (peek().t != T.ID) { ok = false; break }
                    names += next().text
                    if (at(",")) { next(); continue }
                    if (at("]")) { next(); break }
                    ok = false; break
                }
                if (ok && at("=")) {
                    next()
                    val v = rhs()
                    return TupleDecl(names, v, t.line, t.col).also { endOfStatement() }
                }
                p = save
            }
        }
        if (isFuncDef()) {
            val n = next()
            expect("(")
            val params = ArrayList<Pair<String, Expr?>>()
            while (!at(")")) {
                skipType()
                val pn = ident("a parameter name")
                val def = if (at("=")) { next(); expr() } else null
                params += pn.text to def
                if (at(",")) next() else if (!at(")")) err(peek(), "Expected ',' or ')' in the parameter list")
            }
            next(); expect("=>")
            val body = if (peek().t == T.NL) block() else listOf(ExprStmt(expr(), n.line, n.col).also { endOfStatement() })
            return FuncDef(n.text, params, body, n.line, n.col)
        }
        // Typed declaration: float x = ..., series int n = ...
        if (peek().t == T.ID && peek().text in typeWords) {
            var k = 0
            while (peek(k).t == T.ID && peek(k).text in typeWords) k++
            if (k > 0 && peek(k).t == T.ID && atK(k + 1, "=")) {
                p += k
                val n = next(); next()
                return Decl(n.text, rhs(), false, t.line, t.col).also { endOfStatement() }
            }
        }
        if (peek().t == T.ID && peek(1).t == T.OP) {
            val op = peek(1).text
            if (op == "=") {
                val n = next(); next()
                return Decl(n.text, rhs(), false, n.line, n.col).also { endOfStatement() }
            }
            if (op in setOf(":=", "+=", "-=", "*=", "/=", "%=")) {
                val n = next(); next()
                return Assign(n.text, op, rhs(), n.line, n.col).also { endOfStatement() }
            }
        }
        val e = expr()
        if (at("=") ) err(peek(), "Cannot assign to this; declare a variable with 'name = value'")
        return ExprStmt(e, t.line, t.col).also { endOfStatement() }
    }

    private fun skipType() {
        while (peek().t == T.ID && peek().text in typeWords && peek(1).t == T.ID) next()
    }

    /** The right-hand side of = or :=, which may be an if block. */
    private fun rhs(): Expr {
        if (at("if")) { val t = peek(); return IfExpr(ifStmt(inExpr = true), t.line, t.col).tag() }
        return expr()
    }

    private fun ifStmt(inExpr: Boolean = false): If {
        val t = expect("if")
        val cond = expr()
        val then = block()
        var orElse: List<Stmt>? = null
        if (at("else")) {
            next()
            orElse = if (at("if")) listOf(ifStmt(inExpr)) else block()
        }
        return If(cond, then, orElse, t.line, t.col)
    }

    fun expr(): Expr = ternary()

    private fun ternary(): Expr {
        val c = or()
        if (at("?")) {
            val q = next()
            val a = ternary(); expect(":"); val b = ternary()
            return Ternary(c, a, b, q.line, q.col).tag()
        }
        return c
    }
    private fun or(): Expr { var a = and(); while (at("or")) { val o = next(); a = Binary("or", a, and(), o.line, o.col).tag() }; return a }
    private fun and(): Expr { var a = not(); while (at("and")) { val o = next(); a = Binary("and", a, not(), o.line, o.col).tag() }; return a }
    private fun not(): Expr { if (at("not")) { val o = next(); return Unary("not", not(), o.line, o.col).tag() }; return eq() }
    private fun eq(): Expr {
        var a = cmp()
        while (at("==") || at("!=")) { val o = next(); a = Binary(o.text, a, cmp(), o.line, o.col).tag() }
        return a
    }
    private fun cmp(): Expr {
        var a = add()
        while (at("<") || at(">") || at("<=") || at(">=")) { val o = next(); a = Binary(o.text, a, add(), o.line, o.col).tag() }
        return a
    }
    private fun add(): Expr {
        var a = mul()
        while (at("+") || at("-")) { val o = next(); a = Binary(o.text, a, mul(), o.line, o.col).tag() }
        return a
    }
    private fun mul(): Expr {
        var a = unary()
        while (at("*") || at("/") || at("%")) { val o = next(); a = Binary(o.text, a, unary(), o.line, o.col).tag() }
        return a
    }
    private fun unary(): Expr {
        if (at("-") || at("+")) { val o = next(); return Unary(o.text, unary(), o.line, o.col).tag() }
        return postfix()
    }
    private fun postfix(): Expr {
        var e = primary()
        while (at("[")) {
            val o = next()
            val off = expr(); expect("]")
            e = Index(e, off, o.line, o.col).tag()
        }
        return e
    }
    private fun primary(): Expr {
        val t = peek()
        when (t.t) {
            T.NUM -> { next(); return Num(t.num, t.line, t.col).tag() }
            T.STR -> { next(); return Str(t.text, t.line, t.col).tag() }
            T.COLOR -> { next(); return ColorLit(t.text, t.line, t.col).tag() }
            T.ID -> {
                next()
                if (t.text == "na" && !at("(")) return Name("na", t.line, t.col).tag()
                if (at("<")) {
                    // array.new<float>(...) and friends
                    if (peek(1).t == T.ID && atK(2, ">")) err(t, "Arrays and generic types are not supported yet")
                }
                if (at("(")) {
                    next()
                    val args = ArrayList<Arg>()
                    while (!at(")")) {
                        if (peek().t == T.ID && atK(1, "=")) {
                            val n = next().text; next()
                            args += Arg(n, expr())
                        } else {
                            if (args.any { it.name != null }) err(peek(), "A positional argument cannot follow a named one")
                            args += Arg(null, expr())
                        }
                        if (at(",")) next() else if (!at(")")) err(peek(), "Expected ',' or ')' in the call to ${t.text}(), found ${describe(peek())}")
                    }
                    next()
                    return Call(t.text, args, t.line, t.col).tag()
                }
                return Name(t.text, t.line, t.col).tag()
            }
            T.OP -> when (t.text) {
                "(" -> { next(); val e = expr(); expect(")"); return e }
                "[" -> {
                    next()
                    val items = ArrayList<Expr>()
                    while (!at("]")) {
                        items += expr()
                        if (at(",")) next() else if (!at("]")) err(peek(), "Expected ',' or ']' in the list, found ${describe(peek())}")
                    }
                    next()
                    return TupleLit(items, t.line, t.col).tag()
                }
                "true", "false" -> { next(); return Bool(t.text == "true", t.line, t.col).tag() }
                else -> err(t, "Expected a value but found ${describe(t)}")
            }
            else -> err(t, "Expected a value but found ${describe(t)}")
        }
    }
}
