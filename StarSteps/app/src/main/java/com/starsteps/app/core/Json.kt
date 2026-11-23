package com.starsteps.app.core

/** A small JSON reader and writer (the app uses no libraries). */
object Json {
    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Double -> sb.append(if (v.isFinite()) v.toString() else "0")
            is Float -> sb.append(if (v.isFinite()) v.toString() else "0")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(x, sb) }
                sb.append(']')
            }
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
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

    /** Maps, lists, strings, numbers (Long or Double), booleans and nulls. */
    fun read(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.space()
        require(p.i == s.length) { "unexpected text at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun space() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            space()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }
        fun word(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "bad word at $i" }; i += w.length; return v }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            space()
            if (s[i] == '}') { i++; return m }
            while (true) {
                space()
                val k = str()
                space()
                require(s[i] == ':') { "':' expected at $i" }
                i++
                m[k] = value()
                space()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}') { "'}' expected at $i" }
                i++
                return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            space()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                space()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']') { "']' expected at $i" }
                i++
                return l
            }
        }
        fun str(): String {
            require(s[i] == '"') { "'\"' expected at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}

/** The family to and from text (the file the app keeps, and backups). */
object Storage {
    const val VERSION = 1

    fun encode(f: Family): String = Json.write(mapOf(
        "app" to "StarSteps",
        "version" to VERSION,
        "settings" to mapOf("pin" to f.settings.pin, "language" to f.settings.language),
        "children" to f.children.map { mapOf("id" to it.id, "name" to it.name, "birthYear" to it.birthYear, "birthMonth" to it.birthMonth,
            "avatar" to it.avatar, "colour" to it.colour, "goal" to it.goalRewardId) },
        "behaviours" to f.behaviours.map { mapOf("id" to it.id, "child" to it.childId, "name" to it.name, "emoji" to it.emoji,
            "kind" to it.kind.name, "value" to it.value, "consequence" to it.consequence) },
        "rewards" to f.rewards.map { mapOf("id" to it.id, "child" to it.childId, "name" to it.name, "emoji" to it.emoji, "cost" to it.cost) },
        "entries" to f.entries.map { mapOf("id" to it.id, "child" to it.childId, "time" to it.time, "day" to it.day.toString(),
            "type" to it.type.name, "ref" to it.refId, "label" to it.label, "emoji" to it.emoji, "amount" to it.amount, "note" to it.note) },
    ))

    @Suppress("UNCHECKED_CAST")
    fun decode(text: String): Family {
        val root = Json.read(text) as? Map<String, Any?> ?: throw IllegalArgumentException("not a StarSteps file")
        require(root["app"] == "StarSteps") { "not a StarSteps file" }
        fun list(k: String) = (root[k] as? List<Map<String, Any?>>).orEmpty()
        fun Map<String, Any?>.long(k: String) = (this[k] as Number).toLong()
        fun Map<String, Any?>.int(k: String) = (this[k] as? Number)?.toInt() ?: 0
        fun Map<String, Any?>.str(k: String) = this[k] as? String ?: ""
        val f = Family()
        (root["settings"] as? Map<String, Any?>)?.let { f.settings = Settings(it.str("pin"), it.str("language")) }
        for (m in list("children")) f.children += Child(m.long("id"), m.str("name"), m.int("birthYear"), m.int("birthMonth").coerceIn(1, 12),
            m.str("avatar"), m.int("colour"), (m["goal"] as? Number)?.toLong())
        for (m in list("behaviours")) f.behaviours += Behaviour(m.long("id"), m.long("child"), m.str("name"), m.str("emoji"),
            Kind.valueOf(m.str("kind")), m.int("value"), m.str("consequence"))
        for (m in list("rewards")) f.rewards += Reward(m.long("id"), m.long("child"), m.str("name"), m.str("emoji"), m.int("cost"))
        for (m in list("entries")) f.entries += Entry(m.long("id"), m.long("child"), m.long("time"), Day.parse(m.str("day")),
            EntryType.valueOf(m.str("type")), (m["ref"] as? Number)?.toLong(), m.str("label"), m.str("emoji"), m.int("amount"), m.str("note"))
        return f
    }
}
