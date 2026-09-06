package com.eareyereading.tts

/**
 * 腾讯云 TTS 音色目录（纯数据文件，单一职责：音色清单与默认路由）。
 *
 * 从 TencentTtsEngine.kt 抽出：引擎类保留合成/播放/签名逻辑，
 * 音色表是展示层数据（设置页音色选择弹窗直接消费），独立成文件
 * 后增删音色不碰引擎代码（开闭原则）。
 */

/** 按书语言选默认腾讯云音色 id */
fun defaultTencentVoiceForLanguage(language: String?): Int =
    when {
        language == null -> 101001 // 智瑜·女声
        language.startsWith("zh") -> 101001 // 智瑜·中文女声
        else -> 101001 // 智瑜也支持英文
    }

/**
 * 腾讯云 TTS 常用音色。
 * id 来自腾讯云 TTS 文档 VoiceType 参数。
 */
data class TencentVoice(
    val id: Int,
    val displayName: String,
    val language: String,
)

val TENCENT_VOICES: List<TencentVoice> = listOf(
    TencentVoice(101001, "智瑜 · 中文女声（精品）", "zh"),
    TencentVoice(101002, "智瑜 · 中文女声（精品2）", "zh"),
    TencentVoice(101003, "智玲 · 中文女声", "zh"),
    TencentVoice(101004, "智美 · 中文女声", "zh"),
    TencentVoice(101005, "智云 · 中文男声", "zh"),
    TencentVoice(101006, "智莉 · 中文女声", "zh"),
    TencentVoice(101007, "智娜 · 中文女声", "zh"),
    TencentVoice(101008, "智琪 · 中文男声", "zh"),
    TencentVoice(101009, "智杰 · 中文男声", "zh"),
    TencentVoice(101011, "智芸 · 中文女声", "zh"),
    TencentVoice(101012, "智坤 · 中文男声", "zh"),
    TencentVoice(101013, "智丹 · 中文女声", "zh"),
    TencentVoice(101014, "智辉 · 中文男声", "zh"),
    TencentVoice(101015, "智宁 · 中文女声", "zh"),
    TencentVoice(101016, "智燕 · 中文女声", "zh"),
    TencentVoice(101017, "智华 · 中文男声", "zh"),
    TencentVoice(101018, "智燕 · 中文女声2", "zh"),
    TencentVoice(101019, "智辉 · 中文男声2", "zh"),
    TencentVoice(101020, "智蓉 · 中文女声", "zh"),
    TencentVoice(101021, "智靖 · 中文男声", "zh"),
    TencentVoice(101022, "智萱 · 中文女声", "zh"),
    TencentVoice(101023, "智溪 · 中文女声", "zh"),
    TencentVoice(101024, "智谦 · 中文男声", "zh"),
    TencentVoice(101025, "智白 · 中文女声", "zh"),
    TencentVoice(101026, "智宁 · 中文男声", "zh"),
    TencentVoice(101027, "智彤 · 中文女声", "zh"),
    TencentVoice(101028, "智蓝 · 中文女声", "zh"),
    TencentVoice(101029, "智元 · 中文男声", "zh"),
    TencentVoice(101030, "智剑 · 中文男声", "zh"),
    TencentVoice(101031, "智莱 · 中文女声", "zh"),
    TencentVoice(101032, "智香 · 中文女声", "zh"),
    TencentVoice(101033, "智丹 · 中文女声2", "zh"),
    TencentVoice(101034, "智琴 · 中文女声", "zh"),
    TencentVoice(101035, "智婷 · 中文女声", "zh"),
    TencentVoice(101036, "智娥 · 中文女声", "zh"),
    TencentVoice(101037, "智媛 · 中文女声", "zh"),
    TencentVoice(101038, "智华 · 中文女声", "zh"),
    TencentVoice(101039, "智晶 · 中文女声", "zh"),
    TencentVoice(101040, "智悦 · 中文女声", "zh"),
    TencentVoice(101041, "智贝 · 中文女声", "zh"),
    TencentVoice(101042, "智倩 · 中文女声", "zh"),
    TencentVoice(101043, "智歆 · 中文女声", "zh"),
    TencentVoice(101044, "智鹏 · 中文男声", "zh"),
    TencentVoice(101045, "智熙 · 中文男声", "zh"),
    TencentVoice(101046, "智玛 · 中文女声", "zh"),
    TencentVoice(101047, "智波 · 中文男声", "zh"),
    TencentVoice(101048, "智云 · 中文女声", "zh"),
    TencentVoice(101049, "智刚 · 中文男声", "zh"),
    TencentVoice(101050, "智茹 · 中文女声", "zh"),
    TencentVoice(101051, "智铃 · 中文女声", "zh"),
    TencentVoice(101052, "智浩 · 中文男声", "zh"),
    TencentVoice(101053, "智心 · 中文女声", "zh"),
    TencentVoice(101054, "智笙 · 中文男声", "zh"),
    TencentVoice(101055, "智宁 · 中文女声3", "zh"),
    TencentVoice(101056, "智皓 · 中文男声", "zh"),
    TencentVoice(101057, "智柔 · 中文女声", "zh"),
    TencentVoice(101058, "智米 · 中文女声", "zh"),
    TencentVoice(101059, "智果 · 中文女声", "zh"),
    TencentVoice(101060, "智虹 · 中文女声", "zh"),
    TencentVoice(101061, "智娥 · 中文女声2", "zh"),
    TencentVoice(101062, "智言 · 中文女声", "zh"),
    TencentVoice(101063, "智娜 · 中文女声2", "zh"),
    TencentVoice(101064, "智梅 · 中文女声", "zh"),
    TencentVoice(101065, "智桦 · 中文男声", "zh"),
    TencentVoice(101066, "智春 · 中文女声", "zh"),
    TencentVoice(101067, "智健 · 中文男声", "zh"),
    TencentVoice(101068, "智明 · 中文男声", "zh"),
    TencentVoice(101069, "智严 · 中文男声", "zh"),
    TencentVoice(101070, "智静 · 中文女声", "zh"),
    TencentVoice(101071, "智强 · 中文男声", "zh"),
    TencentVoice(101072, "智纯 · 中文女声", "zh"),
    TencentVoice(101073, "智杨 · 中文男声", "zh"),
    TencentVoice(101074, "智韩 · 中文男声", "zh"),
    TencentVoice(101075, "智翠 · 中文女声", "zh"),
    TencentVoice(101076, "智冬 · 中文男声", "zh"),
    TencentVoice(101077, "智凤 · 中文女声", "zh"),
    TencentVoice(101078, "智红 · 中文女声", "zh"),
    TencentVoice(101079, "智龙 · 中文男声", "zh"),
    TencentVoice(101080, "智琴 · 中文女声2", "zh"),
    TencentVoice(101081, "智秀 · 中文女声", "zh"),
    TencentVoice(101082, "智旭 · 中文男声", "zh"),
    TencentVoice(101083, "智阳 · 中文男声", "zh"),
    TencentVoice(101084, "智凡 · 中文男声", "zh"),
    TencentVoice(101085, "智海 · 中文男声", "zh"),
    TencentVoice(101086, "智亮 · 中文男声", "zh"),
    TencentVoice(101087, "智波 · 中文男声2", "zh"),
    TencentVoice(101088, "智霖 · 中文男声", "zh"),
    TencentVoice(101089, "智杰 · 中文男声2", "zh"),
    TencentVoice(101090, "智宇 · 中文男声", "zh"),
    TencentVoice(101091, "智威 · 中文男声", "zh"),
    TencentVoice(101092, "智忠 · 中文男声", "zh"),
    TencentVoice(101093, "智卿 · 中文男声", "zh"),
    TencentVoice(101094, "智丽 · 中文女声", "zh"),
    TencentVoice(101095, "智国 · 中文男声", "zh"),
    TencentVoice(101096, "智星 · 中文男声", "zh"),
    TencentVoice(101097, "智夏 · 中文女声", "zh"),
    TencentVoice(101098, "智雨 · 中文女声", "zh"),
    TencentVoice(101099, "智风 · 中文男声", "zh"),
    TencentVoice(101100, "智雪 · 中文女声", "zh"),
    TencentVoice(101101, "智梦 · 中文女声", "zh"),
)
