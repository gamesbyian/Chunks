package io.github.gamesbyian.chunks.media

/**
 * Minimal, strict, bounded JSON reader/writer for media provider responses and the
 * app-private media store. Provider bodies are untrusted: depth, length and member
 * counts are capped, duplicate keys keep the first value, and nothing is evaluated.
 * Plain JVM (no android.jar stubs), so provider adapters are unit-testable offline.
 */
sealed class Json {
    object Null : Json() { override fun toString() = "null" }
    data class Bool(val value: Boolean) : Json()
    data class Num(val raw: String) : Json() {
        val asLong: Long? get() = raw.toLongOrNull()
        val asDouble: Double? get() = raw.toDoubleOrNull()
    }
    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val members: Map<String, Json>) : Json()

    operator fun get(key: String): Json? = (this as? Obj)?.members?.get(key)
    val string: String? get() = (this as? Str)?.value
    val long: Long? get() = (this as? Num)?.asLong
    val int: Int? get() = long?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    val double: Double? get() = (this as? Num)?.asDouble
    val bool: Boolean? get() = (this as? Bool)?.value
    val array: List<Json> get() = (this as? Arr)?.items ?: emptyList()
    val obj: Map<String, Json> get() = (this as? Obj)?.members ?: emptyMap()

    fun encode(): String = StringBuilder().also { write(this, it) }.toString()

    companion object {
        const val MAX_DEPTH = 32
        const val MAX_CHARS = 2_000_000
        const val MAX_MEMBERS = 20_000

        fun parse(text: String): Json {
            if (text.length > MAX_CHARS) throw MediaJsonException("Response too large")
            val p = Parser(text)
            val v = p.value(0)
            p.ws()
            if (p.i != text.length) throw MediaJsonException("Trailing content")
            return v
        }

        fun obj(vararg pairs: Pair<String, Json?>): Obj =
            Obj(LinkedHashMap<String, Json>().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } })
        fun str(v: String?): Json? = v?.let { Str(it) }
        fun num(v: Long?): Json? = v?.let { Num(it.toString()) }
        fun num(v: Int?): Json? = v?.let { Num(it.toString()) }
        fun num(v: Double?): Json? = v?.takeIf { it.isFinite() }?.let { Num(it.toString()) }
        fun bool(v: Boolean?): Json? = v?.let { Bool(it) }
        fun arr(items: List<Json>): Arr = Arr(items)

        private fun write(v: Json, out: StringBuilder) {
            when (v) {
                Null -> out.append("null")
                is Bool -> out.append(v.value)
                is Num -> out.append(v.raw)
                is Str -> quote(v.value, out)
                is Arr -> { out.append('['); v.items.forEachIndexed { i, x -> if (i > 0) out.append(','); write(x, out) }; out.append(']') }
                is Obj -> {
                    out.append('{')
                    var first = true
                    for ((k, x) in v.members) {
                        if (!first) out.append(','); first = false
                        quote(k, out); out.append(':'); write(x, out)
                    }
                    out.append('}')
                }
            }
        }

        private fun quote(s: String, out: StringBuilder) {
            out.append('"')
            for (c in s) when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
            out.append('"')
        }
    }

    private class Parser(val s: String) {
        var i = 0
        var members = 0

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun fail(msg: String): Nothing = throw MediaJsonException("$msg at $i")

        fun value(depth: Int): Json {
            if (depth > MAX_DEPTH) fail("Nesting too deep")
            ws()
            if (i >= s.length) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> Str(stringValue())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("Unexpected character")
            }
        }

        private fun count() { if (++members > MAX_MEMBERS) fail("Too many members") }

        private fun literal(word: String, v: Json): Json {
            if (!s.startsWith(word, i)) fail("Bad literal")
            i += word.length
            return v
        }

        private fun number(): Json {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("Bad number")
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i].isAsciiDigit()) i++ } else fail("Bad number")
            if (i < s.length && s[i] == '.') { i++; val d = i; while (i < s.length && s[i].isAsciiDigit()) i++; if (i == d) fail("Bad fraction") }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i; while (i < s.length && s[i].isAsciiDigit()) i++; if (i == d) fail("Bad exponent")
            }
            if (i - start > 40) fail("Number too long")
            return Num(s.substring(start, i))
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'

        private fun stringValue(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("Bad unicode escape")
                                sb.append(code.toChar()); i += 4
                            }
                            else -> fail("Bad escape '$e'")
                        }
                    }
                    c < ' ' -> fail("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun arrayValue(depth: Int): Json {
            i++
            val items = ArrayList<Json>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return Arr(items) }
            while (true) {
                count()
                items.add(value(depth + 1))
                ws()
                if (i >= s.length) fail("Unterminated array")
                when (s[i++]) { ',' -> continue; ']' -> return Arr(items); else -> fail("Expected , or ]") }
            }
        }

        private fun objectValue(depth: Int): Json {
            i++
            val map = LinkedHashMap<String, Json>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return Obj(map) }
            while (true) {
                count()
                ws()
                if (i >= s.length || s[i] != '"') fail("Expected key")
                val key = stringValue()
                ws()
                if (i >= s.length || s[i++] != ':') fail("Expected :")
                val v = value(depth + 1)
                if (!map.containsKey(key)) map[key] = v
                ws()
                if (i >= s.length) fail("Unterminated object")
                when (s[i++]) { ',' -> continue; '}' -> return Obj(map); else -> fail("Expected , or }") }
            }
        }
    }
}

class MediaJsonException(message: String) : Exception(message)
