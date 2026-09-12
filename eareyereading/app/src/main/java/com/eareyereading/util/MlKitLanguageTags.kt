package com.eareyereading.util

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * 语言代码 → ML Kit [TranslateLanguage] 常量的映射表。
 *
 * 从 [TranslationHelper] 抽出的纯数据映射（SRP）：把"语言代码解析"这一
 * 独立变化点（新增支持语言时只改本文件）与翻译调度逻辑解耦（OCP）。
 *
 * ML Kit 没有提供按代码查常量的静态方法，这里显式维护一份常用映射；
 * 未支持的语言返回 null（调用方按"无法翻译"判定）。
 */
internal fun mlKitLanguageTag(code: String): String? = when (code.trim().lowercase(Locale.ROOT)) {
    "af" -> TranslateLanguage.AFRIKAANS
    "ar" -> TranslateLanguage.ARABIC
    "be" -> TranslateLanguage.BELARUSIAN
    "bg" -> TranslateLanguage.BULGARIAN
    "bn" -> TranslateLanguage.BENGALI
    "ca" -> TranslateLanguage.CATALAN
    "cs" -> TranslateLanguage.CZECH
    "cy" -> TranslateLanguage.WELSH
    "da" -> TranslateLanguage.DANISH
    "de" -> TranslateLanguage.GERMAN
    "el" -> TranslateLanguage.GREEK
    "en" -> TranslateLanguage.ENGLISH
    "eo" -> TranslateLanguage.ESPERANTO
    "es" -> TranslateLanguage.SPANISH
    "et" -> TranslateLanguage.ESTONIAN
    "fa" -> TranslateLanguage.PERSIAN
    "fi" -> TranslateLanguage.FINNISH
    "fr" -> TranslateLanguage.FRENCH
    "ga" -> TranslateLanguage.IRISH
    "gl" -> TranslateLanguage.GALICIAN
    "gu" -> TranslateLanguage.GUJARATI
    "he" -> TranslateLanguage.HEBREW
    "hi" -> TranslateLanguage.HINDI
    "hr" -> TranslateLanguage.CROATIAN
    "ht" -> TranslateLanguage.HAITIAN_CREOLE
    "hu" -> TranslateLanguage.HUNGARIAN
    "id" -> TranslateLanguage.INDONESIAN
    "is" -> TranslateLanguage.ICELANDIC
    "it" -> TranslateLanguage.ITALIAN
    "ja" -> TranslateLanguage.JAPANESE
    "ko" -> TranslateLanguage.KOREAN
    "lt" -> TranslateLanguage.LITHUANIAN
    "lv" -> TranslateLanguage.LATVIAN
    "mk" -> TranslateLanguage.MACEDONIAN
    "mr" -> TranslateLanguage.MARATHI
    "ms" -> TranslateLanguage.MALAY
    "mt" -> TranslateLanguage.MALTESE
    "nl" -> TranslateLanguage.DUTCH
    "no" -> TranslateLanguage.NORWEGIAN
    "pl" -> TranslateLanguage.POLISH
    "pt" -> TranslateLanguage.PORTUGUESE
    "ro" -> TranslateLanguage.ROMANIAN
    "ru" -> TranslateLanguage.RUSSIAN
    "sk" -> TranslateLanguage.SLOVAK
    "sl" -> TranslateLanguage.SLOVENIAN
    "sq" -> TranslateLanguage.ALBANIAN
    "sv" -> TranslateLanguage.SWEDISH
    "sw" -> TranslateLanguage.SWAHILI
    "ta" -> TranslateLanguage.TAMIL
    "te" -> TranslateLanguage.TELUGU
    "th" -> TranslateLanguage.THAI
    "tl" -> TranslateLanguage.TAGALOG
    "tr" -> TranslateLanguage.TURKISH
    "uk" -> TranslateLanguage.UKRAINIAN
    "ur" -> TranslateLanguage.URDU
    "vi" -> TranslateLanguage.VIETNAMESE
    "zh" -> TranslateLanguage.CHINESE
    else -> null
}
