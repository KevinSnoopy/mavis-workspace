@file:Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")

package com.eareyereading.util

import android.content.Context
import android.os.Build
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 翻译助手
 * 优先级：系统翻译(Android 14+) > ML Kit > 本地词典
 * 懒加载，首次翻译时初始化
 */
@Singleton
class TranslationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var mlkitTranslator: com.google.mlkit.nl.translate.Translate.Translator? = null
    private var mlkitReady = false
    private var translationSession: android.speech.tts.TranslationSession? = null
    private var useSystemTranslation = false
    private var initAttempted = false

    // ── 懒加载初始化（线程安全）─────────────────────
    private suspend fun ensureInitialized() {
        if (initAttempted) return
        initAttempted = true

        // 优先尝试 Android 14 系统翻译
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val ok = initSystemTranslationSync()
            if (ok) { useSystemTranslation = true; return }
        }

        // 降级到 ML Kit（后台预加载模型）
        initMlKitAsync()
    }

    // ── Android 14+ 系统翻译 ─────────────────────
    private fun initSystemTranslationSync(): Boolean {
        return try {
            android.speech.tts.TranslationSessionManager.createSession(
                context,
                object : android.speech.tts.TranslationResultCallback() {
                    // 仅用于初始化 session，不需要处理回调
                }
            ).use { session ->
                translationSession = session
                true
            }
        } catch (e: android.speech.tts.TranslationSessionException) {
            android.util.Log.w("TranslationHelper", "System translation not available: ${e.message}")
            false
        } catch (e: java.lang.RuntimeException) {
            android.util.Log.w("TranslationHelper", "Runtime error initializing system translation: ${e.message}")
            false
        }
    }

    private suspend fun translateViaSystem(text: String): String? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && translationSession != null) {
            try {
                val request = android.speech.tts.TranslationRequest.Builder()
                    .setSourceLocale(android.speech.tts.TranslationVoice.LOCALE_EN_US)
                    .addTargetLocale(android.speech.tts.TranslationVoice.LOCALE_ZH_CN)
                    .setText(text)
                    .build()

                // 正确使用 CancellationSignal：协程取消时自动停止翻译请求
                val cancellationSignal = android.os.CancellationSignal()
                cont.invokeOnCancellation { cancellationSignal.cancel() }

                translationSession?.requestTranslation(
                    request,
                    cancellationSignal,
                    object : android.speech.tts.TranslationResultCallback() {
                        override fun onTranslationResult(result: android.speech.tts.TranslationResult) {
                            val zh = result.getTargetLocaleTranslation(
                                android.speech.tts.TranslationVoice.LOCALE_ZH_CN
                            )?.translatedText ?: result.translatedText
                            cont.resume(zh)
                        }
                        override fun onError(errorCode: Int, errorMsg: CharSequence?) {
                            android.util.Log.w("TranslationHelper", "System translation error: $errorCode $errorMsg")
                            cont.resume(lookupLocalDict(text))
                        }
                    }
                )
            } catch (e: android.speech.tts.TranslationSessionException) {
                android.util.Log.w("TranslationHelper", "Translation request failed: ${e.message}")
                cont.resume(lookupLocalDict(text))
            } catch (e: java.lang.RuntimeException) {
                android.util.Log.w("TranslationHelper", "Runtime error during translation: ${e.message}")
                cont.resume(lookupLocalDict(text))
            }
        } else {
            cont.resume(lookupLocalDict(text))
        }
    }

    // ── ML Kit（Google，依赖 GMS）────────────────
    // 后台异步初始化，不阻塞首次翻译
    private fun initMlKitAsync() {
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build()
            mlkitTranslator = Translation.getClient(options)
            mlkitTranslator?.downloadModelIfNeeded(
                DownloadConditions.Builder().build()
            )?.addOnSuccessListener {
                mlkitReady = true
                android.util.Log.d("TranslationHelper", "ML Kit model downloaded, ready")
            }?.addOnFailureListener { e ->
                android.util.Log.w("TranslationHelper", "ML Kit download failed: ${e.message}")
                mlkitReady = false
            }
        } catch (e: com.google.mlkit.common.MlKitException) {
            android.util.Log.w("TranslationHelper", "ML Kit init failed: ${e.message}")
        } catch (e: java.lang.RuntimeException) {
            android.util.Log.w("TranslationHelper", "Runtime error initializing ML Kit: ${e.message}")
        }
    }

    private suspend fun translateViaMlKit(text: String): String? {
        // ML Kit 未就绪：触发异步初始化，直接查词典
        if (!mlkitReady) {
            android.util.Log.d("TranslationHelper", "ML Kit not ready yet, using dict fallback")
            return lookupLocalDict(text)
        }
        return suspendCancellableCoroutine { cont ->
            mlkitTranslator?.translate(text)
                ?.addOnSuccessListener { translated -> cont.resume(translated) }
                ?.addOnFailureListener {
                    android.util.Log.w("TranslationHelper", "ML Kit translate failed: ${it.message}")
                    cont.resume(lookupLocalDict(text))
                }
                ?: cont.resume(lookupLocalDict(text))
        }
    }

    // ── 主入口 ───────────────────────────────────
    suspend fun translateEnToZh(text: String): String? {
        if (text.isBlank()) return null
        ensureInitialized()  // 首次触发懒加载
        return if (useSystemTranslation) translateViaSystem(text)
               else translateViaMlKit(text)
    }

    suspend fun translateParagraphs(paragraphs: List<String>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        paragraphs.forEachIndexed { index, para ->
            result[index] = if (para.isBlank()) ""
                else translateEnToZh(para.take(4000)) ?: "[翻译失败]"
        }
        return result
    }

    suspend fun translateWord(word: String): String? = translateEnToZh(word)
    suspend fun translateContext(sentence: String): String? = translateEnToZh(sentence)
    suspend fun translateSentence(sentence: String): String? = translateEnToZh(sentence)

    // ── 本地词典（1000 高频词）───────────────────
    private fun lookupLocalDict(text: String): String? {
        val clean = text.trim().lowercase().replace(Regex("[^a-z]"), "")
        if (clean.length < 2) return null
        return localDict[clean]
    }

    fun close() {
        mlkitTranslator?.close()
        translationSession?.close()
        mlkitTranslator = null; translationSession = null
        mlkitReady = false; useSystemTranslation = false
        initAttempted = false  // 允许重新初始化
    }

    private companion object {
        val localDict: Map<String, String> = mapOf(
            // 冠词/介词/连词
            "the" to "the 定冠词", "a" to "a 不定冠词", "an" to "an 不定冠词",
            "of" to "of …的", "to" to "to 到/不定式", "in" to "in 在…里",
            "on" to "on 在…上", "at" to "at 在", "by" to "by 通过/由",
            "for" to "for 为了", "with" to "with 和/用", "without" to "without 没有",
            "from" to "from 从", "about" to "about 关于", "against" to "against 反对",
            "between" to "between 在…之间", "among" to "among 在…之中",
            "through" to "through 通过", "during" to "during 在…期间",
            "until" to "until 直到", "before" to "before 在…之前",
            "after" to "after 在…之后", "over" to "over 在…之上",
            "under" to "under 在…之下", "above" to "above 在…之上",
            "below" to "below 在…之下", "near" to "near 在…附近",
            "beside" to "beside 在…旁边", "behind" to "behind 在…后面",
            "inside" to "inside 在…里面", "outside" to "outside 在…外面",
            "toward" to "toward 朝向", "within" to "within 在…内",
            "and" to "and 和", "or" to "or 或者", "but" to "but 但是",
            "if" to "if 如果", "when" to "when 当…时", "while" to "while 当…时",
            "as" to "as 像/因为", "than" to "than 比", "because" to "because 因为",
            "since" to "since 自从", "unless" to "unless 除非",
            "although" to "although 虽然", "though" to "though 虽然",
            "whether" to "whether 是否", "once" to "once 一旦",
            "however" to "however 然而", "therefore" to "therefore 因此",
            "thus" to "thus 因此", "hence" to "hence 因此",
            "otherwise" to "otherwise 否则",

            // 代词
            "i" to "I 我", "me" to "me 我(宾)", "my" to "my 我的",
            "mine" to "mine 我的东西", "myself" to "myself 我自己",
            "you" to "you 你", "your" to "your 你的", "yours" to "yours 你的东西",
            "yourself" to "yourself 你自己", "he" to "he 他", "him" to "him 他(宾)",
            "his" to "his 他的", "himself" to "himself 他自己",
            "she" to "she 她", "her" to "her 她(宾)/她的", "hers" to "hers 她的东西",
            "herself" to "herself 她自己",
            "it" to "it 它", "its" to "its 它的", "itself" to "itself 它自己",
            "we" to "we 我们", "us" to "us 我们(宾)", "our" to "our 我们的",
            "ours" to "ours 我们的东西", "ourselves" to "ourselves 我们自己",
            "they" to "they 他们", "them" to "them 他们(宾)", "their" to "their 他们的",
            "theirs" to "theirs 他们的东西", "themselves" to "themselves 他们自己",
            "this" to "this 这个", "that" to "that 那个",
            "these" to "these 这些", "those" to "those 那些",
            "who" to "who 谁", "whom" to "whom 谁(宾)", "whose" to "whose 谁的",
            "which" to "which 哪个", "what" to "what 什么",
            "someone" to "someone 某人", "somebody" to "somebody 某人",
            "something" to "something 某事", "anyone" to "anyone 任何人",
            "anybody" to "anybody 任何人", "anything" to "anything 任何事",
            "everyone" to "everyone 每个人", "everybody" to "everybody 每个人",
            "everything" to "everything 每件事", "noone" to "noone 没人",
            "nobody" to "nobody 没人", "nothing" to "nothing 没什么",
            "one" to "one 一个/某人", "each" to "each 每个",
            "every" to "every 每个", "both" to "both 两者都",
            "either" to "either 任一", "neither" to "neither 两者都不",
            "other" to "other 其他", "another" to "another 另一个",
            "such" to "such 这样的", "same" to "same 相同的",

            // 动词 be / have / do
            "be" to "be 是", "am" to "am 是(我)", "is" to "is 是(他)",
            "are" to "are 是(他们)", "was" to "was 是(过去)", "were" to "were 是(过去)",
            "been" to "been 是(完成)", "being" to "being 正在是",
            "have" to "have 有", "has" to "has 有(他)", "had" to "had 有(过去)",
            "having" to "having 正在有",
            "do" to "do 做", "does" to "does 做(他)", "did" to "did 做(过去)",
            "doing" to "doing 正在做", "done" to "done 已完成",

            // 常见动词
            "go" to "go 去", "come" to "come 来", "make" to "make 做/制造",
            "take" to "take 拿", "get" to "get 得到", "know" to "know 知道",
            "see" to "see 看见", "think" to "think 想/认为", "say" to "say 说",
            "tell" to "tell 告诉", "want" to "want 想要", "use" to "use 使用",
            "find" to "find 发现", "give" to "give 给", "tell" to "tell 告诉",
            "try" to "try 尝试", "leave" to "leave 离开", "call" to "call 打电话/叫",
            "keep" to "keep 保持", "let" to "let 让", "begin" to "begin 开始",
            "seem" to "seem 似乎", "help" to "help 帮助", "show" to "show 显示",
            "hear" to "hear 听", "play" to "play 玩", "run" to "run 跑",
            "move" to "move 移动", "live" to "live 居住", "believe" to "believe 相信",
            "hold" to "hold 握住", "bring" to "bring 带来", "happen" to "happen 发生",
            "write" to "write 写", "provide" to "provide 提供", "sit" to "sit 坐",
            "stand" to "stand 站", "lose" to "lose 丢失", "pay" to "pay 付钱",
            "meet" to "meet 遇见", "include" to "include 包含", "continue" to "continue 继续",
            "set" to "set 设置", "learn" to "learn 学习", "change" to "change 改变",
            "lead" to "lead 领导", "understand" to "understand 理解",
            "watch" to "watch 观看", "follow" to "follow 跟随", "stop" to "stop 停止",
            "create" to "create 创造", "speak" to "speak 说", "read" to "read 读",
            "spend" to "spend 花费", "grow" to "grow 生长", "open" to "open 打开",
            "walk" to "walk 走", "win" to "win 赢", "offer" to "offer 提供",
            "remember" to "remember 记得", "love" to "love 爱", "consider" to "consider 考虑",
            "appear" to "appear 出现", "buy" to "buy 买", "wait" to "wait 等",
            "serve" to "serve 服务", "die" to "die 死", "send" to "send 发送",
            "expect" to "expect 期望", "build" to "build 建造", "stay" to "stay 停留",
            "fall" to "fall 落下", "cut" to "cut 切", "reach" to "reach 到达",
            "kill" to "kill 杀", "remain" to "remain 留下", "suggest" to "suggest 建议",
            "raise" to "raise 举起", "pass" to "pass 通过", "sell" to "sell 卖",
            "require" to "require 需要", "report" to "report 报告", "decide" to "decide 决定",
            "pull" to "pull 拉", "develop" to "develop 开发", "hope" to "hope 希望",
            "carry" to "carry 携带", "break" to "break 打破", "receive" to "receive 接收",
            "agree" to "agree 同意", "support" to "support 支持", "hit" to "hit 打",
            "produce" to "produce 生产", "eat" to "eat 吃", "cover" to "cover 覆盖",
            "catch" to "catch 抓住", "draw" to "draw 画/拉", "choose" to "choose 选择",
            "study" to "study 学习", "form" to "form 形成", "attach" to "attach 附上",
            "face" to "face 面对", "deal" to "deal 处理", "must" to "must 必须",
            "should" to "should 应该", "would" to "would 将/会", "could" to "could 能",
            "can" to "can 能", "will" to "will 将要", "may" to "may 可能",
            "might" to "might 可能", "need" to "need 需要", "dare" to "dare 敢",
            "ought" to "ought 应该", "used" to "used 过去常常",

            // 动词时态变体
            "went" to "went go的过去式", "come" to "come 来", "came" to "came come的过去式",
            "made" to "made make的过去式", "took" to "took take的过去式",
            "got" to "got get的过去式", "knew" to "knew know的过去式",
            "saw" to "saw see的过去式", "thought" to "thought think的过去式",
            "told" to "told tell的过去式", "gave" to "gave give的过去式",
            "left" to "left leave的过去式", "called" to "called call的过去式",
            "kept" to "kept keep的过去式", "began" to "began begin的过去式",
            "seemed" to "seemed seem的过去式", "showed" to "showed show的过去式",
            "brought" to "brought bring的过去式", "wrote" to "wrote write的过去式",
            "spent" to "spent spend的过去式", "grew" to "grew grow的过去式",
            "opened" to "opened open的过去式", "walked" to "walked walk的过去式",
            "offered" to "offered offer的过去式", "remembered" to "remembered remember的过去式",
            "loved" to "loved love的过去式", "considered" to "considered consider的过去式",
            "appeared" to "appeared appear的过去式", "bought" to "bought buy的过去式",
            "waited" to "waited wait的过去式", "served" to "served serve的过去式",
            "died" to "died die的过去式", "sent" to "sent send的过去式",
            "built" to "built build的过去式", "fell" to "fell fall的过去式",
            "cut" to "cut 切", "reached" to "reached reach的过去式",
            "killed" to "killed kill的过去式", "suggested" to "suggested suggest的过去式",
            "raised" to "raised raise的过去式", "passed" to "passed pass的过去式",
            "sold" to "sold sell的过去式", "required" to "required require的过去式",
            "reported" to "reported report的过去式", "decided" to "decided decide的过去式",
            "pulled" to "pulled pull的过去式", "developed" to "developed develop的过去式",
            "carried" to "carried carry的过去式", "broke" to "broke break的过去式",
            "agreed" to "agreed agree的过去式", "supported" to "supported support的过去式",
            "hit" to "hit 打", "ate" to "ate eat的过去式", "drawn" to "drawn draw的过去分词",
            "chosen" to "chosen choose的过去分词", "known" to "known know的过去分词",
            "seen" to "seen see的过去分词", "given" to "given give的过去分词",
            "taken" to "taken take的过去分词", "found" to "found find的过去分词",
            "told" to "told tell的过去分词", "left" to "left leave的过去分词",
            "called" to "called call的过去分词", "begun" to "begun begin的过去分词",
            "shown" to "shown show的过去分词", "grown" to "grown grow的过去分词",
            "eaten" to "eaten eat的过去分词", "run" to "run 跑", "ran" to "ran run的过去式",
            "become" to "become 成为", "became" to "became become的过去式",
            "feel" to "feel 感觉", "felt" to "felt feel的过去式",
            "sleep" to "sleep 睡", "slept" to "slept sleep的过去式",
            "wake" to "wake 醒", "woke" to "woke wake的过去式",
            "drink" to "drink 喝", "drank" to "drank drink的过去式",
            "fly" to "fly 飞", "flew" to "flew fly的过去式",
            "sing" to "sing 唱", "sang" to "sang sing的过去式",
            "swim" to "swim 游泳", "swam" to "swam swim的过去式",
            "wear" to "wear 穿", "wore" to "wore wear的过去式",
            "drive" to "drive 驾驶", "drove" to "drove drive的过去式",
            "ride" to "ride 骑", "rode" to "rode ride的过去式",
            "teach" to "teach 教", "taught" to "taught teach的过去式",
            "buy" to "buy 买", "paid" to "paid pay的过去式",
            "fight" to "fight 战斗", "fought" to "fought fight的过去式",
            "win" to "win 赢", "won" to "won win的过去式",
            "lose" to "lose 丢失", "lost" to "lost lose的过去式",
            "meet" to "meet 遇见", "met" to "met meet的过去式",
            "stand" to "stand 站", "stood" to "stood stand的过去式",
            "hear" to "hear 听", "heard" to "heard hear的过去式",
            "say" to "say 说", "said" to "said say的过去式",
            "put" to "put 放", "put" to "put put的过去式",
            "set" to "set 设置", "set" to "set set的过去式",
            "cut" to "cut 切", "cut" to "cut cut的过去式",
            "hit" to "hit 打", "hit" to "hit hit的过去式",
            "let" to "let 让", "let" to "let let的过去式",
            "cost" to "cost 花费", "cost" to "cost cost的过去式",
            "read" to "read 读", "read" to "read read的过去式",
            "sleep" to "sleep 睡", "slept" to "slept sleep的过去式",
            "forget" to "forget 忘记", "forgot" to "forgot forget的过去式",
            "rise" to "rise 上升", "rose" to "rose rise的过去式",
            "fall" to "fall 落下", "fell" to "fell fall的过去式",
            "speak" to "speak 说", "spoke" to "spoke speak的过去式",
            "wake" to "wake 醒", "woke" to "woke wake的过去式",
            "hide" to "hide 藏", "hid" to "hid hide的过去式",
            "bite" to "bite 咬", "bit" to "bit bite的过去式",
            "strike" to "strike 击打", "struck" to "struck strike的过去式",
            "hang" to "hang 挂", "hung" to "hung hang的过去式",
            "sink" to "sink 沉", "sank" to "sank sink的过去式",
            "ring" to "ring 响", "rang" to "rang ring的过去式",
            "spring" to "spring 跳", "sprang" to "sprang spring的过去式",
            "shrink" to "shrink 收缩", "shrank" to "shrank shrink的过去式",
            "drink" to "drink 喝", "drank" to "drank drink的过去式",
            "swear" to "swear 发誓", "swore" to "swore swear的过去式",
            "tear" to "tear 撕", "tore" to "tore tear的过去式",
            "bear" to "bear 忍受", "bore" to "bore bear的过去式",
            "wear" to "wear 穿", "wore" to "wore wear的过去式",
            "draw" to "draw 画", "drew" to "drew draw的过去式",
            "blow" to "blow 吹", "blew" to "blew blow的过去式",
            "grow" to "grow 生长", "grew" to "grew grow的过去式",
            "know" to "know 知道", "knew" to "knew know的过去式",
            "throw" to "throw 扔", "threw" to "threw throw的过去式",
            "show" to "show 显示", "showed" to "showed show的过去式",
            "break" to "break 打破", "broke" to "broke break的过去式",
            "choose" to "choose 选择", "chose" to "chose choose的过去式",
            "freeze" to "freeze 冻结", "froze" to "froze freeze的过去式",
            "speak" to "speak 说", "spoke" to "spoke speak的过去式",
            "steal" to "steal 偷", "stole" to "stole steal的过去式",
            "drive" to "drive 驾驶", "drove" to "drove drive的过去式",
            "arise" to "arise 出现", "arose" to "arose arise的过去式",
            "forbid" to "forbid 禁止", "forbade" to "forbade forbid的过去式",
            "ride" to "ride 骑", "rode" to "rode ride的过去式",
            "write" to "write 写", "wrote" to "wrote write的过去式",
            "hide" to "hide 藏", "hid" to "hid hide的过去式",
            "ride" to "ride 骑", "rode" to "rode ride的过去式",

            // 形容词
            "good" to "good 好的", "bad" to "bad 坏的", "new" to "new 新的",
            "old" to "old 老的/旧的", "young" to "young 年轻的",
            "great" to "great 伟大的", "little" to "little 小的/少的",
            "big" to "big 大的", "small" to "small 小的", "large" to "large 大的",
            "long" to "long 长的", "short" to "short 短的",
            "high" to "high 高的", "low" to "low 低的",
            "wide" to "wide 宽的", "deep" to "deep 深的",
            "thick" to "thick 厚的", "thin" to "thin 薄的",
            "heavy" to "heavy 重的", "light" to "light 轻的",
            "hard" to "hard 硬的/难的", "soft" to "soft 软的",
            "easy" to "easy 容易的", "difficult" to "difficult 困难的",
            "fast" to "fast 快的", "slow" to "slow 慢的",
            "quiet" to "quiet 安静的", "noisy" to "noisy 吵闹的",
            "dark" to "dark 暗的", "bright" to "bright 明亮的",
            "hot" to "hot 热的", "cold" to "cold 冷的",
            "cool" to "cool 凉爽的", "warm" to "warm 温暖的",
            "dry" to "dry 干的", "wet" to "wet 湿的",
            "rich" to "rich 富有的", "poor" to "poor 贫穷的",
            "strong" to "strong 强壮的", "weak" to "weak 虚弱的",
            "healthy" to "healthy 健康的", "ill" to "ill 生病的",
            "happy" to "happy 开心的", "sad" to "sad 悲伤的",
            "angry" to "angry 生气的", "afraid" to "afraid 害怕的",
            "tired" to "tired 累的", "free" to "free 自由的/免费的",
            "busy" to "busy 忙碌的",
            "beautiful" to "beautiful 美丽的", "ugly" to "ugly 丑陋的",
            "important" to "important 重要的", "possible" to "possible 可能的",
            "necessary" to "necessary 必要的", "different" to "different 不同的",
            "same" to "same 相同的", "right" to "right 正确的",
            "wrong" to "wrong 错误的", "true" to "true 真实的",
            "false" to "false 假的", "real" to "real 真实的",
            "natural" to "natural 自然的", "special" to "special 特别的",
            "common" to "common 常见的", "certain" to "certain 确定的",
            "clear" to "clear 清晰的", "late" to "late 晚的",
            "early" to "early 早的", "public" to "public 公共的",
            "private" to "private 私人的", "safe" to "safe 安全的",
            "dangerous" to "dangerous 危险的", "famous" to "famous 著名的",
            "popular" to "popular 流行的", "serious" to "serious 严肃的",
            "strange" to "strange 奇怪的", "wonderful" to "wonderful 极好的",
            "terrible" to "terrible 可怕的", "exciting" to "exciting 令人兴奋的",
            "boring" to "boring 无聊的", "interesting" to "interesting 有趣的",
            "nice" to "nice 好的", "kind" to "kind 和善的",
            "cruel" to "cruel 残忍的", "brave" to "brave 勇敢的",
            "careful" to "careful 仔细的", "careless" to "careless 粗心的",
            "polite" to "polite 礼貌的", "rude" to "rude 粗鲁的",
            "honest" to "honest 诚实的", "clever" to "clever 聪明的",
            "stupid" to "stupid 愚蠢的", "funny" to "funny 有趣的",
            "usual" to "usual 通常的", "enough" to "enough 足够的",
            "able" to "able 能够的", "unable" to "unable 不能的",
            "ready" to "ready 准备好的", "alone" to "alone 独自的",
            "together" to "together 一起", "dead" to "dead 死的",
            "alive" to "alive 活着的", "asleep" to "asleep 睡着的",
            "awake" to "awake 醒着的", "alone" to "alone 单独的",
            "proud" to "proud 骄傲的", "angry" to "angry 生气的",
            "anxious" to "anxious 焦虑的", "proud" to "proud 骄傲的",
            "ashamed" to "ashamed 羞耻的", "grateful" to "grateful 感激的",
            "guilty" to "guilty 内疚的", "jealous" to "jealous 嫉妒的",
            "curious" to "curious 好奇的", "eager" to "eager 热切的",
            "generous" to "generous 慷慨的", "gentle" to "gentle 温和的",
            "patient" to "patient 耐心的", "strict" to "strict 严格的",
            "tender" to "tender 温柔的", "wild" to "wild 野生的",
            "calm" to "calm 平静的", "eager" to "eager 渴望的",
            "firm" to "firm 坚定的", "fair" to "fair 公平的",
            "pure" to "pure 纯净的", "plain" to "plain 朴素的",
            "royal" to "royal 皇家的", "smooth" to "smooth 平滑的",
            "rough" to "rough 粗糙的", "sharp" to "sharp 锋利的",
            "stiff" to "stiff 僵硬的", "loose" to "loose 松的",
            "tight" to "tight 紧的", "empty" to "empty 空的",
            "full" to "full 满的", "bare" to "bare 赤裸的",
            "blind" to "blind 瞎的", "deaf" to "deaf 聋的",
            "naked" to "naked 赤裸的", "dumb" to "dumb 哑的",
            "bitter" to "bitter 苦的", "sweet" to "sweet 甜的",
            "sour" to "sour 酸的", "salty" to "salty 咸的",
            "fresh" to "fresh 新鲜的", "ripe" to "ripe 成熟的",
            "raw" to "raw 生的", "cooked" to "cooked 煮熟的",
            "married" to "married 已婚的", "single" to "single 单身的",
            "nervous" to "nervous 紧张的", "proud" to "proud 骄傲的",
            "confident" to "confident 自信的", "shy" to "shy 害羞的",
            "stupid" to "stupid 愚蠢的", "wise" to "wise 明智的",
            "foolish" to "foolish 愚蠢的", "silly" to "silly 傻的",
            "crazy" to "crazy 疯狂的", "mad" to "mad 疯狂的",
            "angry" to "angry 愤怒的", "furious" to "furious 狂怒的",
            "proud" to "proud 骄傲的", "humble" to "humble 谦逊的",
            "modest" to "modest 谦虚的", "arrogant" to "arrogant 傲慢的",
            "mean" to "mean 卑鄙的", "noble" to "noble 高尚的",
            "selfish" to "selfish 自私的", "generous" to "generous 慷慨的",
            "greedy" to "greedy 贪婪的", "lazy" to "lazy 懒惰的",
            "diligent" to "diligent 勤奋的", "active" to "active 积极的",
            "passive" to "passive 被动的", "positive" to "positive 积极的",
            "negative" to "negative 消极的", "optimistic" to "optimistic 乐观的",
            "pessimistic" to "pessimistic 悲观的", "formal" to "formal 正式的",
            "informal" to "informal 非正式的", "official" to "official 官方的",
            "political" to "political 政治的", "religious" to "religious 宗教的",
            "scientific" to "scientific 科学的", "historical" to "historical 历史的",
            "cultural" to "cultural 文化的", "economic" to "economic 经济的",
            "social" to "social 社会的", "personal" to "personal 个人的",
            "public" to "public 公共的", "private" to "private 私人的",
            "civil" to "civil 公民的", "military" to "military 军事的",
            "international" to "international 国际的", "national" to "national 国家的",
            "local" to "local 当地的", "foreign" to "foreign 外国的",
            "western" to "western 西方的", "eastern" to "eastern 东方的",
            "northern" to "northern 北方的", "southern" to "southern 南方的",
            "central" to "central 中心的", "modern" to "modern 现代的",
            "ancient" to "ancient 古代的", "classical" to "classical 古典的",
            "traditional" to "traditional 传统的", "contemporary" to "contemporary 当代的",
            "previous" to "previous 以前的", "current" to "current 当前的",
            "former" to "former 以前的", "late" to "late 已故的",
            "recent" to "recent 最近的", "gradual" to "gradual 渐进的",
            "sudden" to "sudden 突然的", "gradual" to "gradual 逐渐的",
            "immediate" to "immediate 立即的", "temporary" to "temporary 临时的",
            "permanent" to "permanent 永久的", "final" to "final 最终的",
            "last" to "last 最后的", "next" to "next 下一个",
            "previous" to "previous 上一个", "first" to "first 第一个",
            "second" to "second 第二个", "third" to "third 第三个",
            "main" to "main 主要的", "major" to "major 主要的",
            "minor" to "minor 次要的", "primary" to "primary 主要的",
            "secondary" to "secondary 次要的", "basic" to "basic 基本的",
            "fundamental" to "fundamental 基本的", "advanced" to "advanced 高级的",
            "elementary" to "elementary 初级的", "simple" to "simple 简单的",
            "complicated" to "complicated 复杂的", "complex" to "complex 复杂的",
            "perfect" to "perfect 完美的", "absolute" to "absolute 绝对的",
            "relative" to "relative 相对的", "complete" to "complete 完整的",
            "total" to "total 总的", "entire" to "entire 整个的",
            "extreme" to "extreme 极端的", "moderate" to "moderate 温和的",
            "violent" to "violent 暴力的", "gentle" to "gentle 温和的",
            "mild" to "mild 温和的", "severe" to "severe 严重的",
            "slight" to "slight 轻微的", "tiny" to "tiny 微小的",
            "massive" to "massive 巨大的", "vast" to "vast 广阔的",
            "broad" to "broad 宽阔的", "narrow" to "narrow 狭窄的",
            "flat" to "flat 平坦的", "steep" to "steep 陡峭的",
            "bent" to "bent 弯曲的", "straight" to "straight 直的",
            "round" to "round 圆的", "square" to "square 正方形的",
            "circular" to "circular 圆形的", "triangular" to "triangular 三角形的",
            "solid" to "solid 固体的", "liquid" to "liquid 液体的",
            "gas" to "gas 气体的", "mental" to "mental 精神的",
            "physical" to "physical 身体的", "spiritual" to "spiritual 精神的",
            "emotional" to "emotional 情感的", "rational" to "rational 理性的",
            "sensitive" to "sensitive 敏感的", "innocent" to "innocent 无辜的",
            "experienced" to "experienced 有经验的", "skilled" to "skilled 熟练的",
            "unskilled" to "unskilled 不熟练的", "talented" to "talented 有天赋的",
            "gifted" to "gifted 有天赋的", "brilliant" to "brilliant 出色的",
            "ordinary" to "ordinary 普通的", "outstanding" to "outstanding 杰出的",
            "excellent" to "excellent 优秀的", "terrific" to "terrific 极好的",
            "awful" to "awful 糟糕的", "rotten" to "rotten 腐烂的",
            "wealthy" to "wealthy 富有的", "wealthier" to "wealthier 更富的",
            "wealthiest" to "wealthiest 最富的", "poorer" to "poorer 更穷的",
            "poorest" to "poorest 最穷的", "richer" to "richer 更富的",
            "richest" to "richest 最富的", "happier" to "happier 更开心的",
            "happiest" to "happiest 最开心的", "sadder" to "sadder 更悲伤的",
            "saddest" to "saddest 最悲伤的", "easier" to "easier 更容易的",
            "easiest" to "easiest 最容易的", "harder" to "harder 更难的",
            "hardest" to "hardest 最难的", "better" to "better 更好的",
            "best" to "best 最好的", "worse" to "worse 更差的",
            "worst" to "worst 最差的", "more" to "more 更多的",
            "most" to "most 最多的", "less" to "less 更少的",
            "least" to "least 最少的", "further" to "further 更远的",
            "furthest" to "furthest 最远的", "older" to "older 更老的",
            "oldest" to "oldest 最老的", "younger" to "younger 更年轻的",
            "youngest" to "youngest 最年轻的", "higher" to "higher 更高的",
            "highest" to "highest 最高的", "lower" to "lower 更低的",
            "lowest" to "lowest 最低的", "longer" to "longer 更长的",
            "longest" to "longest 最长的", "shorter" to "shorter 更短的",
            "shortest" to "shortest 最短的", "earlier" to "earlier 更早的",
            "earliest" to "earliest 最早的", "later" to "later 更晚的",
            "latest" to "latest 最晚的", "bigger" to "bigger 更大的",
            "biggest" to "biggest 最大的", "smaller" to "smaller 更小的",
            "smallest" to "smallest 最小的",

            // 副词
            "very" to "very 非常", "much" to "much 非常", "quite" to "quite 相当",
            "rather" to "rather 相当", "too" to "too 太", "also" to "also 也",
            "only" to "only 只", "just" to "just 只是", "ever" to "ever 曾经",
            "still" to "still 仍然", "already" to "already 已经", "yet" to "yet 还",
            "once" to "once 一次", "twice" to "twice 两次",
            "here" to "here 这里", "there" to "there 那里",
            "where" to "where 哪里", "when" to "when 什么时候",
            "why" to "why 为什么", "how" to "how 怎样",
            "always" to "always 总是", "never" to "never 从不",
            "often" to "often 经常", "sometimes" to "sometimes 有时",
            "usually" to "usually 通常", "occasionally" to "occasionally 偶尔",
            "rarely" to "rarely 很少", "seldom" to "seldom 很少",
            "constantly" to "constantly 不断地", "continuously" to "continuously 连续地",
            "forever" to "forever 永远", "together" to "together 一起",
            "alone" to "alone 独自", "apart" to "apart 分开",
            "away" to "away 离开", "back" to "back 回来",
            "ahead" to "ahead 向前", "around" to "around 周围",
            "aside" to "aside 在旁边", "nearby" to "nearby 在附近",
            "abroad" to "abroad 在国外", "indoors" to "indoors 在室内",
            "outdoors" to "outdoors 在户外", "upstairs" to "upstairs 在楼上",
            "downstairs" to "downstairs 在楼下", "somewhere" to "somewhere 某处",
            "anywhere" to "anywhere 任何地方", "everywhere" to "everywhere 到处",
            "nowhere" to "nowhere 无处", "somehow" to "somehow 不知怎么地",
            "anyhow" to "anyhow 无论如何",
            "almost" to "almost 几乎", "nearly" to "nearly 几乎",
            "exactly" to "exactly 精确地", "especially" to "especially 尤其",
            "particularly" to "particularly 特别地", "generally" to "generally 通常",
            "specifically" to "specifically 特别地", "mainly" to "mainly 主要地",
            "mostly" to "mostly 主要地", "largely" to "largely 主要地",
            "partly" to "partly 部分地", "mostly" to "mostly 大部分",
            "simply" to "simply 简单地", "merely" to "merely 仅仅",
            "purely" to "purely 完全地", "absolutely" to "absolutely 完全地",
            "completely" to "completely 完全地", "totally" to "totally 完全地",
            "fully" to "fully 完全地", "entirely" to "entirely 完全地",
            "truly" to "truly 真正地", "really" to "really 真的",
            "actually" to "actually 实际上", "indeed" to "indeed 确实",
            "certainly" to "certainly 当然", "surely" to "surely 确定地",
            "definitely" to "definitely 明确地", "probably" to "probably 可能",
            "possibly" to "possibly 可能", "perhaps" to "perhaps 也许",
            "maybe" to "maybe 也许", "apparently" to "apparently 显然地",
            "obviously" to "obviously 显然", "clearly" to "clearly 清楚地",
            "exactly" to "exactly 精确地", "precisely" to "precisely 精确地",
            "approximately" to "approximately 大约", "roughly" to "roughly 大约",
            "about" to "about 大约", "around" to "around 大约",
            "nearly" to "nearly 几乎", "almost" to "almost 几乎",
            "already" to "already 已经", "still" to "still 仍然",
            "yet" to "yet 还", "now" to "now 现在",
            "then" to "then 然后", "soon" to "soon 不久",
            "shortly" to "shortly 很快", "quickly" to "quickly 快速地",
            "slowly" to "slowly 缓慢地", "fast" to "fast 快",
            "rapidly" to "rapidly 快速地", "swiftly" to "swiftly 迅速地",
            "suddenly" to "suddenly 突然", "immediately" to "immediately 立即",
            "eventually" to "eventually 最终", "finally" to "finally 最后",
            "later" to "later 稍后", "before" to "before 之前",
            "after" to "after 之后", "ago" to "ago 以前",
            "recently" to "recently 最近", "lately" to "lately 最近",
            "today" to "today 今天", "tonight" to "tonight 今晚",
            "tomorrow" to "tomorrow 明天", "yesterday" to "yesterday 昨天",
            "therefore" to "therefore 因此", "thus" to "thus 因此",
            "hence" to "hence 因此", "otherwise" to "otherwise 否则",
            "however" to "however 然而", "nevertheless" to "nevertheless 尽管如此",
            "still" to "still 仍然", "yet" to "yet 还",
            "besides" to "besides 此外", "moreover" to "moreover 此外",
            "furthermore" to "furthermore 而且", "instead" to "instead 代替",
            "anyway" to "anyway 无论如何", "besides" to "besides 此外",
            "instead" to "instead 作为替代", "meanwhile" to "meanwhile 与此同时",

            // 数词
            "one" to "one 一", "two" to "two 二", "three" to "three 三",
            "four" to "four 四", "five" to "five 五", "six" to "six 六",
            "seven" to "seven 七", "eight" to "eight 八", "nine" to "nine 九",
            "ten" to "ten 十", "eleven" to "eleven 十一", "twelve" to "twelve 十二",
            "thirteen" to "thirteen 十三", "fourteen" to "fourteen 十四",
            "fifteen" to "fifteen 十五", "sixteen" to "sixteen 十六",
            "seventeen" to "seventeen 十七", "eighteen" to "eighteen 十八",
            "nineteen" to "nineteen 十九", "twenty" to "twenty 二十",
            "thirty" to "thirty 三十", "forty" to "forty 四十",
            "fifty" to "fifty 五十", "sixty" to "sixty 六十",
            "seventy" to "seventy 七十", "eighty" to "eighty 八十",
            "ninety" to "ninety 九十", "hundred" to "hundred 百",
            "thousand" to "thousand 千", "million" to "million 百万",
            "billion" to "billion 十亿", "first" to "first 第一",
            "second" to "second 第二", "third" to "third 第三",
            "fourth" to "fourth 第四", "fifth" to "fifth 第五",
            "sixth" to "sixth 第六", "seventh" to "seventh 第七",
            "eighth" to "eighth 第八", "ninth" to "ninth 第九",
            "tenth" to "tenth 第十", "once" to "once 一次",
            "twice" to "twice 两次", "double" to "double 两倍",
            "triple" to "triple 三倍", "both" to "both 两者",
            "twice" to "twice 两倍", "few" to "few 很少",
            "several" to "several 几个", "many" to "many 很多",
            "a" to "a 一(个)", "an" to "an 一(个)",
            "no" to "no 没有", "some" to "some 一些",
            "any" to "any 任何", "all" to "all 全部",
            "most" to "most 大多数", "other" to "other 其他",
            "another" to "another 另一个",

            // 名词 - 人/职业
            "people" to "people 人们", "person" to "person 人",
            "man" to "man 男人", "woman" to "woman 女人",
            "child" to "child 孩子", "children" to "children 孩子们",
            "baby" to "baby 婴儿", "boy" to "boy 男孩", "girl" to "girl 女孩",
            "friend" to "friend 朋友", "family" to "family 家庭",
            "father" to "father 父亲", "mother" to "mother 母亲",
            "parent" to "parent 父母", "brother" to "brother 兄弟",
            "sister" to "sister 姐妹", "son" to "son 儿子",
            "daughter" to "daughter 女儿", "husband" to "husband 丈夫",
            "wife" to "wife 妻子", "uncle" to "uncle 叔叔",
            "aunt" to "aunt 阿姨", "cousin" to "cousin 堂/表兄弟姐妹",
            "grandfather" to "grandfather 祖父", "grandmother" to "grandmother 祖母",
            "student" to "student 学生", "teacher" to "teacher 老师",
            "doctor" to "doctor 医生", "nurse" to "nurse 护士",
            "police" to "police 警察", "lawyer" to "lawyer 律师",
            "worker" to "worker 工人", "manager" to "manager 经理",
            "president" to "president 总统/校长", "king" to "king 国王",
            "queen" to "queen 女王", "hero" to "hero 英雄",
            "author" to "author 作者", "artist" to "artist 艺术家",
            "scientist" to "scientist 科学家", "engineer" to "engineer 工程师",
            "soldier" to "soldier 士兵", "secretary" to "secretary 秘书",
            "assistant" to "assistant 助手", "director" to "director 导演/主任",
            "chef" to "chef 厨师", "driver" to "driver 司机",
            "pilot" to "pilot 飞行员", "actor" to "actor 男演员",
            "actress" to "actress 女演员", "musician" to "musician 音乐家",
            "painter" to "painter 画家", "writer" to "writer 作家",
            "professor" to "professor 教授", "researcher" to "researcher 研究员",
            "judge" to "judge 法官", "witness" to "witness 证人",
            "victim" to "victim 受害者", "prisoner" to "prisoner 囚犯",
            "enemy" to "enemy 敌人", "guest" to "guest 客人",
            "host" to "host 主人", "neighbor" to "neighbor 邻居",
            "partner" to "partner 伙伴", "colleague" to "colleague 同事",
            "audience" to "audience 观众", "customer" to "customer 顾客",
            "client" to "client 客户", "patient" to "patient 病人",
            "refugee" to "refugee 难民", "citizen" to "citizen 公民",

            // 名词 - 身体部位
            "head" to "head 头", "face" to "face 脸",
            "eye" to "eye 眼睛", "ear" to "ear 耳朵",
            "nose" to "nose 鼻子", "mouth" to "mouth 嘴",
            "tooth" to "tooth 牙齿", "teeth" to "teeth 牙齿(复)",
            "tongue" to "tongue 舌头", "hair" to "hair 头发",
            "neck" to "neck 脖子", "shoulder" to "shoulder 肩膀",
            "arm" to "arm 手臂", "hand" to "hand 手",
            "finger" to "finger 手指", "leg" to "leg 腿",
            "foot" to "foot 脚", "feet" to "feet 脚(复)",
            "knee" to "knee 膝盖", "back" to "back 背",
            "chest" to "chest 胸部", "heart" to "heart 心脏",
            "brain" to "brain 大脑", "skin" to "skin 皮肤",
            "bone" to "bone 骨头", "blood" to "blood 血",
            "voice" to "voice 声音", "mind" to "mind 头脑",

            // 名词 - 物品/日常
            "book" to "book 书", "page" to "page 页", "word" to "word 单词",
            "sentence" to "sentence 句子", "story" to "story 故事",
            "novel" to "novel 小说", "paper" to "paper 纸/论文",
            "pen" to "pen 笔", "pencil" to "pencil 铅笔",
            "desk" to "desk 桌子", "chair" to "chair 椅子",
            "table" to "table 桌子", "bed" to "bed 床",
            "room" to "room 房间", "house" to "house 房子",
            "home" to "home 家", "building" to "building 建筑物",
            "door" to "door 门", "window" to "window 窗户",
            "wall" to "wall 墙", "floor" to "floor 地板",
            "roof" to "roof 屋顶", "stairs" to "stairs 楼梯",
            "car" to "car 汽车", "bus" to "bus 公共汽车",
            "train" to "train 火车", "ship" to "ship 船",
            "plane" to "plane 飞机", "bicycle" to "bicycle 自行车",
            "phone" to "phone 电话", "computer" to "computer 电脑",
            "machine" to "machine 机器", "tool" to "tool 工具",
            "food" to "food 食物", "water" to "water 水",
            "milk" to "milk 牛奶", "meat" to "meat 肉",
            "bread" to "bread 面包", "rice" to "rice 米饭",
            "egg" to "egg 蛋", "fruit" to "fruit 水果",
            "vegetable" to "vegetable 蔬菜", "salt" to "salt 盐",
            "sugar" to "sugar 糖", "coffee" to "coffee 咖啡",
            "tea" to "tea 茶", "wine" to "wine 葡萄酒",
            "beer" to "beer 啤酒", "oil" to "oil 油",
            "clothes" to "clothes 衣服", "shirt" to "shirt 衬衫",
            "dress" to "dress 连衣裙", "coat" to "coat 外套",
            "shoe" to "shoe 鞋", "hat" to "hat 帽子",
            "bag" to "bag 包", "money" to "money 钱",
            "coin" to "coin 硬币", "note" to "note 纸币",
            "card" to "card 卡片", "key" to "key 钥匙",
            "letter" to "letter 信", "email" to "email 电子邮件",
            "news" to "news 新闻", "message" to "message 消息",
            "information" to "information 信息", "data" to "data 数据",
            "fact" to "fact 事实", "truth" to "truth 真相",
            "idea" to "idea 主意", "thought" to "thought 想法",
            "reason" to "reason 原因", "knowledge" to "knowledge 知识",
            "question" to "question 问题", "answer" to "answer 答案",
            "problem" to "problem 问题", "solution" to "solution 解决方案",
            "choice" to "choice 选择", "decision" to "decision 决定",
            "plan" to "plan 计划", "result" to "result 结果",
            "effect" to "effect 效果", "cause" to "cause 原因",
            "purpose" to "purpose 目的", "chance" to "chance 机会",
            "power" to "power 力量/电力", "energy" to "energy 能量",
            "force" to "force 力", "law" to "law 法律",
            "rule" to "rule 规则", "right" to "right 权利",
            "duty" to "duty 责任", "job" to "job 工作",
            "business" to "business 商业", "trade" to "trade 贸易",
            "market" to "market 市场", "price" to "price 价格",
            "cost" to "cost 成本", "value" to "value 价值",
            "tax" to "tax 税", "profit" to "profit 利润",
            "benefit" to "benefit 利益", "risk" to "risk 风险",
            "change" to "change 改变", "growth" to "growth 增长",
            "progress" to "progress 进步", "success" to "success 成功",
            "failure" to "failure 失败", "achievement" to "achievement 成就",

            // 名词 - 地点
            "city" to "city 城市", "town" to "town 城镇",
            "village" to "village 村庄", "country" to "country 国家",
            "nation" to "nation 民族/国家", "world" to "world 世界",
            "earth" to "earth 地球", "land" to "land 土地",
            "street" to "street 街道", "road" to "road 道路",
            "river" to "river 河流", "lake" to "lake 湖",
            "sea" to "sea 海", "ocean" to "ocean 海洋",
            "mountain" to "mountain 山", "island" to "island 岛屿",
            "forest" to "forest 森林", "desert" to "desert 沙漠",
            "beach" to "beach 海滩", "field" to "field 田地",
            "garden" to "garden 花园", "park" to "park 公园",
            "school" to "school 学校", "college" to "college 学院",
            "university" to "university 大学", "hospital" to "hospital 医院",
            "church" to "church 教堂", "temple" to "temple 寺庙",
            "museum" to "museum 博物馆", "library" to "library 图书馆",
            "station" to "station 车站", "airport" to "airport 机场",
            "port" to "port 港口", "office" to "office 办公室",
            "factory" to "factory 工厂", "prison" to "prison 监狱",
            "court" to "court 法院", "theater" to "theater 剧院",
            "restaurant" to "restaurant 餐厅", "hotel" to "hotel 酒店",
            "shop" to "shop 商店", "store" to "store 商店",
            "market" to "market 市场", "bank" to "bank 银行",

            // 名词 - 自然/时间
            "sun" to "sun 太阳", "moon" to "moon 月亮",
            "star" to "star 星星", "sky" to "sky 天空",
            "cloud" to "cloud 云", "rain" to "rain 雨",
            "snow" to "snow 雪", "wind" to "wind 风",
            "storm" to "storm 暴风雨", "fire" to "fire 火",
            "water" to "water 水", "earth" to "earth 地球",
            "air" to "air 空气", "light" to "light 光",
            "darkness" to "darkness 黑暗", "shadow" to "shadow 影子",
            "space" to "space 空间", "time" to "time 时间",
            "year" to "year 年", "month" to "month 月",
            "week" to "week 周", "day" to "day 天",
            "hour" to "hour 小时", "minute" to "minute 分钟",
            "second" to "second 秒", "morning" to "morning 早晨",
            "afternoon" to "afternoon 下午", "evening" to "evening 傍晚",
            "night" to "night 夜晚", "today" to "today 今天",
            "tomorrow" to "tomorrow 明天", "yesterday" to "yesterday 昨天",
            "spring" to "spring 春天", "summer" to "summer 夏天",
            "autumn" to "autumn 秋天", "winter" to "winter 冬天",
            "history" to "history 历史", "future" to "future 未来",
            "past" to "past 过去", "present" to "present 现在",
            "moment" to "moment 时刻", "period" to "period 时期",
            "age" to "age 年龄/时代", "century" to "century 世纪",

            // 名词 - 动物/植物
            "animal" to "animal 动物", "dog" to "dog 狗",
            "cat" to "cat 猫", "bird" to "bird 鸟",
            "fish" to "fish 鱼", "horse" to "horse 马",
            "cow" to "cow 母牛", "sheep" to "sheep 绵羊",
            "pig" to "pig 猪", "chicken" to "chicken 鸡",
            "duck" to "duck 鸭子", "rabbit" to "rabbit 兔子",
            "mouse" to "mouse 老鼠", "snake" to "snake 蛇",
            "wolf" to "wolf 狼", "lion" to "lion 狮子",
            "tiger" to "tiger 老虎", "elephant" to "elephant 大象",
            "monkey" to "monkey 猴子", "bear" to "bear 熊",
            "deer" to "deer 鹿", "fox" to "fox 狐狸",
            "tree" to "tree 树", "flower" to "flower 花",
            "grass" to "grass 草", "leaf" to "leaf 叶子",
            "root" to "root 根", "seed" to "seed 种子",
            "fruit" to "fruit 水果", "plant" to "plant 植物",
            "garden" to "garden 花园", "forest" to "forest 森林",

            // 名词 - 其他
            "god" to "god 神", "life" to "life 生活/生命",
            "death" to "death 死亡", "war" to "war 战争",
            "peace" to "peace 和平", "love" to "love 爱",
            "hate" to "hate 恨", "fear" to "fear 恐惧",
            "hope" to "hope 希望", "joy" to "joy 快乐",
            "anger" to "anger 愤怒", "pain" to "pain 疼痛",
            "pleasure" to "pleasure 快乐", "pride" to "pride 骄傲",
            "shame" to "shame 羞耻", "guilt" to "guilt 内疚",
            "peace" to "peace 和平", "war" to "war 战争",
            "society" to "society 社会", "culture" to "culture 文化",
            "religion" to "religion 宗教", "science" to "science 科学",
            "technology" to "technology 技术", "art" to "art 艺术",
            "music" to "music 音乐", "film" to "film 电影",
            "game" to "game 游戏", "sport" to "sport 运动",
            "race" to "race 种族/赛跑", "class" to "class 班级/阶级",
            "race" to "race 种族", "war" to "war 战争",
            "government" to "government 政府", "army" to "army 军队",
            "company" to "company 公司", "team" to "team 团队",
            "group" to "group 组", "party" to "party 聚会/党",
            "union" to "union 工会/联盟", "club" to "club 俱乐部",
            "association" to "association 协会", "organization" to "organization 组织",

            // 感叹词/其他
            "yes" to "yes 是", "no" to "no 不", "okay" to "okay 好的",
            "please" to "please 请", "thanks" to "thanks 谢谢",
            "sorry" to "sorry 对不起", "hello" to "hello 你好",
            "goodbye" to "goodbye 再见", "wow" to "wow 哇",
            "oh" to "oh 哦", "ah" to "ah 啊",
            "ouch" to "ouch 哎哟", "great" to "great 太棒了",
            "amazing" to "amazing 惊人的", "awesome" to "awesome 极好",
            "cool" to "cool 酷", "nice" to "nice 好",
            "fine" to "fine 好", "bad" to "bad 坏的",
            "terrible" to "terrible 可怕的", "worse" to "worse 更差",
            "worst" to "worst 最差", "better" to "better 更好",
            "best" to "best 最好", "enough" to "enough 足够",
            "rather" to "rather 相当", "quite" to "quite 相当",
            "pretty" to "pretty 相当", "fairly" to "fairly 相当",
            "yet" to "yet 还", "still" to "still 仍然",
            "even" to "even 甚至", "just" to "just 刚刚",
            "only" to "only 只", "almost" to "almost 几乎",
            "nearly" to "nearly 几乎", "probably" to "probably 可能",
            "maybe" to "maybe 也许", "perhaps" to "perhaps 也许",
            "possibly" to "possibly 可能", "certainly" to "certainly 当然",
            "definitely" to "definitely 肯定", "exactly" to "exactly 精确",
            "simply" to "simply 仅仅", "merely" to "merely 仅仅",
            "especially" to "especially 尤其", "particularly" to "particularly 特别",
            "generally" to "generally 通常", "usually" to "usually 通常",
            "sometimes" to "sometimes 有时", "occasionally" to "occasionally 偶尔",
            "rarely" to "rarely 很少", "seldom" to "seldom 很少",
            "never" to "never 从不", "always" to "always 总是",
            "constantly" to "constantly 不断地", "continuously" to "continuously 连续",
            "forever" to "forever 永远", "abroad" to "abroad 在国外",
            "away" to "away 离开", "back" to "back 回来",
            "ahead" to "ahead 向前", "around" to "around 周围",
            "outside" to "outside 在外面", "inside" to "inside 在里面",
            "upstairs" to "upstairs 在楼上", "downstairs" to "downstairs 在楼下",
            "somewhere" to "somewhere 某处", "anywhere" to "anywhere 任何地方",
            "everywhere" to "everywhere 到处", "nowhere" to "nowhere 无处",
            "therefore" to "therefore 因此", "thus" to "thus 因此",
            "hence" to "hence 因此", "however" to "however 然而",
            "otherwise" to "otherwise 否则", "instead" to "instead 代替",
            "besides" to "besides 此外", "moreover" to "moreover 此外",
            "furthermore" to "furthermore 而且", "meanwhile" to "meanwhile 与此同时",
            "nevertheless" to "nevertheless 尽管如此", "though" to "though 虽然",
            "although" to "although 虽然", "whereas" to "whereas 然而",
            "since" to "since 自从/因为", "because" to "because 因为",
            "until" to "until 直到", "unless" to "unless 除非",
            "whether" to "whether 是否", "while" to "while 当…时",
            "once" to "once 一旦", "as" to "as 像/因为",

            // 短语介词/连词
            "because" to "because 因为", "instead" to "instead 代替",
            "throughout" to "throughout 贯穿", "outside" to "outside 在…外面",
            "inside" to "inside 在…里面", "towards" to "towards 朝向",
            "away" to "away 离开", "back" to "back 回来",
            "along" to "along 沿着", "across" to "across 穿过",
            "past" to "past 经过", "onto" to "onto 到…上",
            "into" to "into 进入", "out" to "out 出去",
            "off" to "off 离开", "down" to "down 向下",
            "up" to "up 向上", "round" to "round 围绕",
            "near" to "near 靠近", "aside" to "aside 在旁边",
            "apart" to "apart 分开", "ahead" to "ahead 在前面",
        )
    }
}
