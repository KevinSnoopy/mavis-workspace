package com.eareyereading

import android.app.Application
import com.eareyereading.util.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EareyeReadingApp : Application() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        // 初始化通知渠道
        notificationHelper.createNotificationChannel()
    }
}
