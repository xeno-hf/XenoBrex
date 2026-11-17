package xb.container

/**
 * 自家檔案格式（.xbx／.xbl）用的最小 JSON：物件、陣列、字串、數字、true／false／null。
 * 物件保留鍵的順序（LinkedHashMap）；整數讀成 Long，有小數或指數的讀成 Double。
 * 解析嚴格：結尾多出東西、未跳脫的控制字元、不完整的 \u 都拒絕。
 */
object Json {
    class ParseException(message: String) : Exception(message)

    /** 一份 JSON 最多幾個值；manifest 遠小於這個數，擋掉惡意的大陣列。 */
    private const val MAX_VALUES = 1_000_000

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> string(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Double -> { require(!v.isNaN() && !v.isInfinite()) { "JSON cannot hold $v" }; sb.append(v) }
            is Float -> write(v.toDouble(), sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(k as String, sb); sb.append(':'); write(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(x, sb) }
                sb.append(']')
            }
            is LongArray -> write(v.asList(), sb)
            is IntArray -> write(v.asList(), sb)
            else -> error("JSON cannot hold ${v::class.simpleName}")
        }
    }

    private fun string(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw ParseException("Unexpected text at offset ${p.i}")
        return v
    }

    /** 物件；不是物件就丟 [ParseException]。 */
    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: throw ParseException("Not a JSON object")

    private class Parser(val s: String) {
        var i = 0
        private var values = 0

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun value(depth: Int): Any? {
            if (depth > 64) throw ParseException("Nested too deeply")
            if (++values > MAX_VALUES) throw ParseException("Too many values")
            if (i >= s.length) throw ParseException("Unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("Unexpected text at offset $i")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw ParseException("Expected a key at offset $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw ParseException("Expected ':' at offset $i")
                i++; ws()
                if (m.containsKey(k)) throw ParseException("Duplicate key \"$k\"")
                m[k] = value(depth + 1)
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return m }
                throw ParseException("Expected ',' or '}' at offset $i")
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l += value(depth + 1); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return l }
                throw ParseException("Expected ',' or ']' at offset $i")
            }
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseException("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> throw ParseException("Control character in string")
                    c != '\\' -> sb.append(c)
                    else -> {
                        if (i >= s.length) throw ParseException("Unterminated string")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("Incomplete \\u escape")
                                val h = s.substring(i, i + 4)
                                if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw ParseException("Bad \\u escape")
                                sb.append(h.toInt(16).toChar()); i += 4
                            }
                            else -> throw ParseException("Bad escape \\$e")
                        }
                    }
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) throw ParseException("Unexpected text at offset $start")
            if (s[i] == '0') i++ else while (i < s.length && s[i].isDigit()) i++
            var frac = false
            if (i < s.length && s[i] == '.') { frac = true; i++; if (i >= s.length || !s[i].isDigit()) throw ParseException("Bad number"); while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                frac = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) throw ParseException("Bad number")
                while (i < s.length && s[i].isDigit()) i++
            }
            val t = s.substring(start, i)
            return if (frac) t.toDouble() else t.toLongOrNull() ?: throw ParseException("Number out of range: $t")
        }
    }
}
