package com.eareyereading.util

/**
 * Collins COBUILD 3 词表（字母序，4226 条字面量）。
 *
 * 数据来源：Collins COBUILD Active Study Dictionary 词频数据。
 * 本表为纯数据，由 [CollinsClassifier] 读取并做小写归一化后用于分级查询。
 *
 * ── 重构说明（13 条软件设计原则）──
 * 词表按字母区间物理拆分为 3 个分片文件（见 [CollinsStar3WordsPart1] 等），
 * 本文件只做惰性合并。拆分仅为降低单文件体积，**词条内容与 Set 语义完全不变**。
 */
internal val CollinsStar3Words: Set<String> by lazy {
    buildSet<String>(4226) {
        addAll(CollinsStar3WordsPart1)
        addAll(CollinsStar3WordsPart2)
        addAll(CollinsStar3WordsPart3)
    }
}
