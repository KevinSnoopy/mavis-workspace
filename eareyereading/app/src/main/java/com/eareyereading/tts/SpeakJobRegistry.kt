package com.eareyereading.tts

import kotlinx.coroutines.Job

/**
 * 活跃 speak() 协程的注册表：stop() 全量取消（含正在出声的与挂在锁上等待的）。
 *
 * 从 [EmbeddedTtsEngine] 抽出的单一职责类（SRP）：引擎主类不再直接管理
 * Job 集合与锁，通过本类注册/注销/全量取消。
 *
 * **为什么需要**：单值 Job 字段不够——speak A 持锁播放、speak B 挂在锁上
 * 等待时，stop() 只会取消后注册的 B，A 的句循环跨过下一句继续出声——
 * 停止看似无效。必须取消"所有"调用者。
 *
 * 线程安全：[speakJobLock] 保护 [activeSpeakJobs] 的读写。
 */
internal class SpeakJobRegistry {
    private val speakJobLock = Any()
    private val activeSpeakJobs = mutableSetOf<Job>()

    /** 注册一个活跃 speak 协程的 Job（进入 speakMutex.withLock 前调用） */
    fun register(job: Job?) {
        if (job == null) return
        synchronized(speakJobLock) {
            activeSpeakJobs.add(job)
        }
    }

    /** 注销一个已完成的 speak 协程的 Job（finally 块中调用） */
    fun unregister(job: Job?) {
        if (job == null) return
        synchronized(speakJobLock) {
            activeSpeakJobs.remove(job)
        }
    }

    /**
     * 取消全部活跃 speak 协程并清空注册表。
     * stop() 调用：让 doSpeakQueueLocked 立刻退出（协程取消时
     * kotlinx coroutines Mutex.withLock 会在 finally 释放锁）。
     */
    fun cancelAll() {
        synchronized(speakJobLock) {
            activeSpeakJobs.forEach { it.cancel() }
            activeSpeakJobs.clear()
        }
    }
}
