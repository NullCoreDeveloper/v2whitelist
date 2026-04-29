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
         * Регистрирует периодическое задание на обновление подписки.
         * Учитывает настройки пользователя (вкл/выкл и интервал).
         */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            
            // Проверяем, включено ли автообновление
            val isEnabled = com.kiktor.v2whitelist.handler.MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, true)
            if (!isEnabled) {
                workManager.cancelUniqueWork(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
                Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: cancelled (disabled in settings)")
                return
            }

            // Читаем интервал (в минутах)
            val intervalStr = com.kiktor.v2whitelist.handler.MmkvManager.decodeSettingsString(AppConfig.SUBSCRIPTION_AUTO_UPDATE_INTERVAL, AppConfig.SUBSCRIPTION_DEFAULT_UPDATE_INTERVAL)
            val intervalMinutes = intervalStr?.toLongOrNull() ?: 60L
            val finalInterval = if (intervalMinutes < 15) 15L else intervalMinutes // WorkManager min interval is 15m

            val request = PeriodicWorkRequestBuilder<SubscriptionUpdaterWorker>(
                finalInterval, TimeUnit.MINUTES
            ).build()

            // Используем REPLACE вместо KEEP, чтобы новые настройки интервала применились сразу
            workManager.enqueueUniquePeriodicWork(
                AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: scheduled (every $finalInterval minutes)")
        }

        /**
         * Запускает обновление подписки немедленно (один раз).
         */
        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<SubscriptionUpdaterWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: one-time update triggered manually")
        }
    }
}
