package com.eareyereading.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TtsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentLocale = Locale.US
    private var pendingLanguage: String? = null
    private var pendingContinuations = mutableListOf<kotlin.coroutines.Continuation<Boolean>>()

    suspend fun initialize(language: String = "en"): Boolean = suspendCancellableCoroutine { cont ->
        // 已初始化完成，直接返回
        if (isInitialized && tts != null) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }

        // 正在初始化中，加入等待队列
        if (tts != null && !isInitialized) {
            pendingContinuations.add(cont)
            return@suspendCancellableCoroutine
        }

        pendingContinuations.add(cont)
        pendingLanguage = language

        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                val lang = pendingLanguage ?: "en"
                currentLocale = when (lang) {
                    "zh" -> Locale.SIMPLIFIED_CHINESE
                    "ja" -> Locale.JAPANESE
                    "fr" -> Locale.FRENCH
                    "de" -> Locale.GERMAN
                    "es" -> Locale("es", "ES")
                    else -> Locale.US
                }
                tts?.language = currentLocale
            }
            // 唤醒所有等待的协程
            val pending = pendingContinuations.toList()
            pendingContinuations.clear()
            pending.forEach { it.resume(isInitialized) }
        }
    }

    fun setLanguage(language: String) {
        currentLocale = when (language) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "ja" -> Locale.JAPANESE
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "es" -> Locale("es", "ES")
            else -> Locale.US
        }
        tts?.language = currentLocale
    }

    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed)
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized) return

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onComplete?.invoke() }
            override fun onError(utteranceId: String?) {}
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_${System.currentTimeMillis()}")
    }

    /**
     * 逐句朗读 — 每个句子完成时触发 onSentenceDone
     */
    fun speakSentences(sentences: List<String>, onSentenceDone: (Int) -> Unit, onAllDone: () -> Unit) {
        if (!isInitialized || sentences.isEmpty()) {
            onAllDone()
            return
        }
        var index = 0

        fun speakNext() {
            if (index >= sentences.size) {
                onAllDone()
                return
            }
            val sentence = sentences[index]
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    onSentenceDone(index)
                    index++
                    speakNext()
                }
                override fun onError(utteranceId: String?) {
                    index++
                    speakNext()
                }
            })
            tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "sentence_$index")
        }
        speakNext()
    }

    fun stop() {
        tts?.stop()
    }

    fun pause() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        pendingContinuations.clear()
    }
}
