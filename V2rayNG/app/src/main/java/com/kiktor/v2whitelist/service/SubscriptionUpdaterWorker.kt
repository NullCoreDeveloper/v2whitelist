package com.kiktor.v2whitelist.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.SmartConnectManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager задание для автообновления подписки раз в час.
 * Запускается в фоне даже когда приложение закрыто.
 * Показывает беззвучное уведомление пока обновляется.
 */
class SubscriptionUpdaterWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notifManager = NotificationManagerCompat.from(applicationContext)
    private val notifId = 42

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: starting background subscription update")
        showNotification()
        return try {
            SmartConnectManager.updateSubscription(applicationContext)
            Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: subscription updated successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "SubscriptionUpdaterWorker: failed to update subscription", e)
            Result.retry()
        } finally {
            notifManager.cancel(notifId)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showNotification() {
        // Создаём тихий канал (IMPORTANCE_MIN = без звука, без вибрации, без иконки в статус-баре)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConfig.SUBSCRIPTION_UPDATE_CHANNEL,
                AppConfig.SUBSCRIPTION_UPDATE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            notifManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, AppConfig.SUBSCRIPTION_UPDATE_CHANNEL)
            .setContentTitle(applicationContext.getString(R.string.status_updating_subscription))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setPriority(NotificationCompat.PRIORITY_MIN)   // минимальный приоритет = беззвучное
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()

        notifManager.notify(notifId, notification)
    }

    companion object {
        /**
         * Регистрирует периодическое задание на обновление подписки раз в час.
         * Вызывать из AngApplication.onCreate() только в главном процессе.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionUpdaterWorker>(
                1, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // не перезапускаем если уже запланировано
                request
            )
            Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: scheduled (every 1 hour)")
        }
    }
}
