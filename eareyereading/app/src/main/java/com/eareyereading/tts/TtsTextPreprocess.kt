package com.eareyereading.tts

/**
 * sherpa-onnx 朗读前的文本预处理：数字/货币/缩写/CJK 归一化（Piper 专用重清洗、
 * Kokoro 轻量清洗）与纯逐句切分。全部为无状态纯函数。
 */
/**
 * 把文本里 sherpa-onnx Piper 模型不认识的字符替换成可发音的等价物，
 * 并把所有 CJK 中日韩字符替换为占位符以保证纯英文 TTS 行为。
 *
 * 为什么强制过滤 CJK（即使书里偶尔出现中文 / 引用 / 跳跃来源）：
 *  1) 当前内置只有 Piper 英文男声，无中英双语模型可用——CJK 字符进 generate()
 *     模型无法产生对应音，会触发 OOV → 静音或段错误。
 *  2) Piper G2P 对 [0-9]+ 'year' 这种组合的归一化在不同语料下表现不稳定，
 *     偶发被听成单字拼音风味（如 "2026" 像 "er ling er liu"），
 *     与我们目标"标准英文朗读"不符。
 *  3) 只读英文书时 CJK 段一般是标题 / 作者名 / 引用，朗读意义不大，
 *     替换为占位符可以提高可听性（"Title: [Chinese text omitted]"）。
 *
 * 已知 OOV 列表（来自实际 logcat）：
 *  - 数字 '0'-'99'：被 Ignore OOV 直接跳过，导致 tensor 索引越界 → SIGSEGV
 *  - 标点 'í'（西班牙语重音字符）、'—'（em-dash）、'"' '"'（smart quotes）、'(' ')'：同样 OOV
 *  - '$' '&' '+' '@' '#' '%' '=' '<' '>' '\\' '`' '~' '^' '|' 等特殊符号
 *  - CJK Unified Ideographs (U+4E00–U+9FFF)、CJK Ext A/B (U+3400–U+4DBF, U+20000+)、
 *    Hiragana (U+3040–U+309F)、Katakana (U+30A0–U+30FF)、Hangul (U+AC00–U+D7AF)：
 *    用 [CJK] 占位
 *
 * 替换策略：
 *  - 4 位年份 (2026) → "twenty twenty-six"，避免被切成 "twenty" + "twenty-six"
 *  - 其他数字 (65, 28, 10, 07) → 英文单词
 *  - 标点 → ASCII 等价（'—' → ", ", '"' → '"'）
 *  - 货币、特殊符号 → 英文读法
 *  - CJK 字符整段 → "[Chinese/Korean text]" 占位符
 */
private object TtsPreprocess {
    val YEAR = Regex("\\b(1\\d{3}|20\\d{2})\\b")
    val TIME = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
    val CURRENCY = Regex("\\$(\\d+)?(?:\\.(\\d{1,2}))?")
    val NUMBER = Regex("(?<!\\d)(\\d+)(?!\\d|:)")
    val CJK_RUN = Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF\\u3040-\\u309F\\u30A0-\\u30FF\\uAC00-\\uD7AF]+)")
    val UPPERCASE_RUN = Regex("\\b[A-Z]{3,}\\b")
    val WHITESPACE = Regex("\\s+")
    val TIME_SUFFIX = Regex("\\d{1,2}:\\d{1,2}$")

    val US = Regex("\\bU\\.S\\.\\b")
    val USA = Regex("\\bU\\.S\\.A\\.\\b")
    val UK = Regex("\\bU\\.K\\.\\b")
    val EU = Regex("\\bE\\.U\\.\\b")
    val PM_DOTTED = Regex("\\bP\\.M\\.\\b")
    val AM_DOTTED = Regex("\\bA\\.M\\.\\b")
    val DC = Regex("\\bD\\.C\\.\\b")
    val NY = Regex("\\bN\\.Y\\.\\b")
    val PM = Regex("\\bPM\\b")
    val AM = Regex("\\bAM\\b")
    val AP = Regex("\\bAP\\b")
    val CEO = Regex("\\bCEO\\b")
    val GDP = Regex("\\bGDP\\b")
    val NASA = Regex("\\bNASA\\b")
    val FBI = Regex("\\bFBI\\b")
    val CIA = Regex("\\bCIA\\b")

    /** 时区缩写正则缓存（disambiguateTimeZoneAbb 的 abbrev 参数只有 4 个取值）。 */
    private val tzAbbRegexes = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    fun tzAbbRegex(abbrev: String): Regex =
        tzAbbRegexes.computeIfAbsent(abbrev) { Regex("\\b$it\\b") }

    /** 单字符 → 读法/替身的单趟映射（替代约 35 次逐字符 String.replace 扫描）。 */
    val LITERAL_REPLACEMENTS: Map<Char, String> = mapOf(
        '\u2014' to ", ",   // em-dash → comma+space
        '\u2013' to "-",    // en-dash → hyphen
        '\u2018' to "'",    // left single quote
        '\u2019' to "'",    // right single quote
        '\u201C' to "\"",   // left double quote
        '\u201D' to "\"",   // right double quote
        '\u00ed' to "i",    // í → i (Rodríguez → Rodriguez)
        '\u00e9' to "e",    // é → e
        '\u00e1' to "a",    // á → a
        '\u00f1' to "n",    // ñ → n
        '\u00fc' to "u",    // ü → u
        '\u00e7' to "c",    // ç → c
        // 货币符号
        '$' to " dollars ",
        '\u20ac' to " euros ",
        '\u00a3' to " pounds ",
        '\u00a5' to " yen ",
        // 其他常见 OOV 符号（含 '<' '>' —— 此前遗漏未替换）
        '@' to " at ",
        '&' to " and ",
        '+' to " plus ",
        '=' to " equals ",
        '#' to " number ",
        '%' to " percent ",
        '\\' to " ",
        '/' to " ",        // 日期斜杠
        '<' to " less than ",
        '>' to " greater than ",
        '*' to " ",
        '[' to ", ",
        ']' to ", ",
        '_' to " ",
        '{' to ", ",
        '}' to ", ",
        '(' to ", ",
        ')' to ", ",
        '\u00a0' to " ",   // non-breaking space
        '`' to "'",        // backtick
        '|' to " ",
        '^' to " ",
        '~' to " ",
        '\u2026' to "...", // ellipsis
    )

    /** 无特殊字符时原样返回（免 StringBuilder）；有则单趟替换。 */
    fun applyLiteralReplacements(s: String): String {
        var hasSpecial = false
        for (c in s) {
            if (c in LITERAL_REPLACEMENTS) {
                hasSpecial = true
                break
            }
        }
        if (!hasSpecial) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            val rep = LITERAL_REPLACEMENTS[c]
            if (rep != null) sb.append(rep) else sb.append(c)
        }
        return sb.toString()
    }

    // numberToWords 的词表（原实现每次调用重建 3 个数组）
    val UNITS = arrayOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    val TEENS = arrayOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen",
    )
    val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety",
    )

    // digitsToWords 的数字名映射（原实现每次调用重建 Map）
    val DIGIT_NAMES: Map<Char, String> = mapOf(
        '0' to "zero", '1' to "one", '2' to "two", '3' to "three",
        '4' to "four", '5' to "five", '6' to "six", '7' to "seven",
        '8' to "eight", '9' to "nine",
    )
}

/**
 * 朗读前文本归一化（句子级热路径，正则/词表全部预编译见 [TtsPreprocess]）。
 */
internal fun preprocessForTts(text: String): String {
    var s = text

    // 4 位年份 (1000-2099)：转成英文单词
    // 注意：要在普通数字转换之前，避免 "2026" 被切成 "two thousand" + "twenty-six"
    s = TtsPreprocess.YEAR.replace(s) { match ->
        numberToWords(match.value.toInt())
    }

    // 时间格式 "10:07" → "ten oh seven"
    s = TtsPreprocess.TIME.replace(s) { match ->
        val (h, m) = match.groupValues[1] to match.groupValues[2]
        "${numberToWords(h.toInt())} oh ${numberToWords(m.toInt())}"
    }

    // 货币 + 数字组合（如 "$100"）：必须在通用数字转换之前，
    // 否则 "$100" 会先变成 "$one hundred" 再变成 " dollars one hundred"（语序颠倒）
    // 整数部分可选：$.50 / $0.99 也要命中（旧正则要求 $ 后紧跟数字，
    // "$.50" 漏匹配后 "$" 被兜底替换成 " dollars " → 读成 "dollars point fifty"）
    s = TtsPreprocess.CURRENCY.replace(s) { match ->
        fun words(digits: String): String =
            digits.toIntOrNull()?.takeIf { it in 0..9999 }?.let { numberToWords(it) }
                ?: digitsToWords(digits)
        val dollars = match.groupValues[1]
        val cents = match.groupValues[2]
        when {
            cents.isEmpty() && dollars.isEmpty() -> match.value // 裸 "$"：交给后续兜底替换
            cents.isEmpty() -> "${words(dollars)} dollars"
            dollars.isEmpty() || dollars == "0" -> "${words(cents)} cents"
            else -> "${words(dollars)} dollars ${words(cents)} cents"
        }
    }

    // 其他数字 (含小数)：转英文
    // 不含已处理过的年份/时间。
    // 关键：超过 Int 或超过支持范围的数字必须逐位转成单词，
    // 绝不能把裸数字留给 generate()——本文件注释明确记载数字会触发
    // native tensor 索引越界 SIGSEGV，且信号无法被 catch 拦截
    s = TtsPreprocess.NUMBER.replace(s) { match ->
        val num = match.value.toIntOrNull()
        if (num != null && num in 0..9999) {
            numberToWords(num)
        } else {
            digitsToWords(match.value)
        }
    }

    // 单字符符号替换：约 35 个逐字符 String.replace 合并为单趟扫描
    //（每个命中字符原先都要全串拷贝一次）
    s = TtsPreprocess.applyLiteralReplacements(s)

    // CJK 强制过滤：把连续中日韩段替换为占位符（参见函数头注释）。
    // 用 capture group + lookahead 实现"整段连续 CJK" 的合并替换，单字符替换的话
    // 每字一字 placeholder，TTS 会读得稀碎。
    s = TtsPreprocess.CJK_RUN.replace(s) { match ->
        // 短中文段（如 "的"）直接沉默；长段提示用户已跳过
        if (match.value.length <= 3) " " else " [Chinese or other text omitted] "
    }

    // 常见缩写展开（MeloTTS lexicon 不含这些，G2P fallback 可能触发 native 空指针）
    s = s.replace(TtsPreprocess.US, "United States")
    s = s.replace(TtsPreprocess.USA, "United States of America")
    s = s.replace(TtsPreprocess.UK, "United Kingdom")
    s = s.replace(TtsPreprocess.EU, "European Union")
    s = s.replace(TtsPreprocess.PM_DOTTED, "P M")
    s = s.replace(TtsPreprocess.AM_DOTTED, "A M")
    s = s.replace(TtsPreprocess.DC, "D C")
    s = s.replace(TtsPreprocess.NY, "New York")
    // 时间缩写 PM/AM（无点号的全大写）
    s = s.replace(TtsPreprocess.PM, "P M")
    s = s.replace(TtsPreprocess.AM, "A M")
    // 时区缩写 ET/CT/PT/MT：语境白名单启发式（2.10）——
    // 仅当明确是时间/时段语境才展开为时区名，否则按缩写逐字母读，
    // 避免 "CT scan" 被误读为 "Central Time scan"。
    s = disambiguateTimeZoneAbb(s, "ET", "Eastern Time")
    s = disambiguateTimeZoneAbb(s, "CT", "Central Time")
    s = disambiguateTimeZoneAbb(s, "PT", "Pacific Time")
    s = disambiguateTimeZoneAbb(s, "MT", "Mountain Time")
    s = s.replace(TtsPreprocess.AP, "Associated Press")
    s = s.replace(TtsPreprocess.CEO, "C E O")
    s = s.replace(TtsPreprocess.GDP, "G D P")
    s = s.replace(TtsPreprocess.NASA, "N A S A")
    s = s.replace(TtsPreprocess.FBI, "F B I")
    s = s.replace(TtsPreprocess.CIA, "C I A")

    // 把连续 3+ 大写字母拆成单字母（如 "NATO" → "N A T O"），
    // MeloTTS lexicon 有单字母发音，避免 G2P 对未知缩写崩溃
    s = TtsPreprocess.UPPERCASE_RUN.replace(s) { match ->
        match.value.toCharArray().joinToString(" ")
    }

    // 把连续空白合并
    s = s.replace(TtsPreprocess.WHITESPACE, " ").trim()
    return s
}

/**
 * Kokoro 双语模型的轻量预处理：仅合并空白。
 *
 * Kokoro 前端自带完整 G2P（espeak-ng 英文 + jieba/词典中文 + ruleFst 数字
 * 归一化），数字、缩写、中英混排、全半角标点均可原生朗读。Piper 专用的
 * 数字→英文单词、CJK→占位符、括号替换等操作在这里反而有害（把 "2026 年"
 * 改成 "twenty twenty-six 年"、把中文整段替换成 "[Chinese text omitted]"）。
 */
internal fun preprocessForTtsLight(text: String): String =
    text.replace(TtsPreprocess.WHITESPACE, " ").trim()

/**
 * 时区缩写歧义消解（2.10，词典白名单启发式）：
 * ET/CT/PT/MT 既是时区名也是通用缩写，不能无条件展开——
 * "CT scan"（计算机断层扫描）会被误读成 "Central Time scan"。
 * 仅当明确是时间/时段语境时展开为时区名，否则按缩写逐字母读（"C T scan"）。
 * 语境判定白名单：后跟 time/am/pm，或前跟数字（小时 / HH:mm）。
 */
private fun disambiguateTimeZoneAbb(s: String, abbrev: String, timezone: String): String {
    return TtsPreprocess.tzAbbRegex(abbrev).replace(s) { m ->
        val after = s.substring(m.range.last + 1).trimStart()
        val before = s.substring(0, m.range.first).trimEnd()
        val timeContext =
            after.startsWith("time", ignoreCase = true) ||
                after.startsWith("am", ignoreCase = true) ||
                after.startsWith("pm", ignoreCase = true) ||
                before.lastOrNull()?.isDigit() == true ||
                TtsPreprocess.TIME_SUFFIX.containsMatchIn(before)
        if (timeContext) timezone else abbrev.map(Char::toString).joinToString(" ")
    }
}

/**
 * 整数 → 英文单词（0-9999）。超过 9999 逐位读出。
 * 永不返回裸数字字符串——裸数字进 generate() 是文档记载的
 * native SIGSEGV 类别（见 preprocessForTts 注释）。
 */
private fun numberToWords(n: Int): String {
    if (n < 0) return digitsToWords(n.toString().removePrefix("-"))
    if (n > 9999) return digitsToWords(n.toString())
    if (n == 0) return "zero"

    val units = TtsPreprocess.UNITS
    val teens = TtsPreprocess.TEENS
    val tens = TtsPreprocess.TENS

    fun under1000(x: Int): String {
        if (x == 0) return ""
        val hundreds = x / 100
        val rest = x % 100
        val h = if (hundreds > 0) "${units[hundreds]} hundred " else ""
        val r = when {
            rest == 0 -> ""
            rest < 10 -> units[rest]
            rest < 20 -> teens[rest - 10]
            else -> "${tens[rest / 10]}${if (rest % 10 > 0) " ${units[rest % 10]}" else ""}"
        }
        return "$h$r".trim()
    }

    val thousands = n / 1000
    val rest = n % 1000
    val t = if (thousands > 0) "${under1000(thousands)} thousand " else ""
    return "$t${under1000(rest)}".trim()
}

/** 数字串逐位读出（电话号/编号/超范围数值），保证不留裸数字。 */
private fun digitsToWords(digits: String): String {
    val names = TtsPreprocess.DIGIT_NAMES
    return digits.mapNotNull { names[it] }.joinToString(" ").ifEmpty { "zero" }
}

/**
 * 纯逐句切分（不累积）。
 * 中文全角句点 。！？；（允许尾随闭引号/括号）：中文散文不靠空白分句，
 * 此前只认 ASCII 边界，整段中文被当成一个"句子"再被 150 字符截断，
 * 而默认模型恰是 MeloTTS 中英——等于中文书每段只读前 150 字。
 * ASCII 边界保留原规则：句末标点 + 空白 + 下一句开头（大写/引号/左括号/数字）。
 */
internal fun splitSentences(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val cjkBoundary = Regex("(?<=[。！？；][”’」』]?)")
    val asciiBoundary = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(\\d])")
    return text.split(cjkBoundary)
        .flatMap { it.split(asciiBoundary) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
