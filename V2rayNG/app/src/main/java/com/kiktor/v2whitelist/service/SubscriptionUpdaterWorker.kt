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
        Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: starting background subscription update (sequential mode)")
        showNotification()
        return try {
            // sequential=true: перебираем зеркала по одному, без параллельных GlobalScope-корутин.
            // В фоне торопиться некуда — экономим RAM и CPU.
            SmartConnectManager.updateSubscription(applicationContext, sequential = true)
            Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: subscription updated successfully")
            Result.success()
        } catch (e: Exception) {
            // ВАЖНО: используем success() а не retry()!
            // У PeriodicWork retry накапливается и после N попыток WorkManager переводит задачу
            // в состояние FAILED — и она больше никогда не запустится.
            // Для фонового обновления подписки ошибка не критична: попробуем в следующий раз.
            Log.e(AppConfig.TAG, "SubscriptionUpdaterWorker: update failed, will retry on next schedule", e)
            Result.success()
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
         * Регистрирует периодическое задание при старте приложения.
         * Использует KEEP: не трогает уже живую задачу, чтобы не сбивать таймер.
         * Если задача умерла (FAILED/CANCELLED) — WorkManager сам её пересоздаст.
         */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)

            val isEnabled = com.kiktor.v2whitelist.handler.MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, true)
            if (!isEnabled) {
                workManager.cancelUniqueWork(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
                Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: cancelled (disabled in settings)")
                return
            }

            val request = buildRequest(context)

            // KEEP: если задача уже работает — не трогаем её (не сбиваем таймер!).
            // Задача пересоздаётся только при первом запуске или после смерти.
            workManager.enqueueUniquePeriodicWork(
                AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: schedule() called (KEEP policy)")
        }

        /**
         * Принудительно пересоздаёт задачу с новыми параметрами.
         * Вызывать когда пользователь изменил настройки (интервал, вкл/выкл).
         */
        fun reschedule(context: Context) {
            val workManager = WorkManager.getInstance(context)

            val isEnabled = com.kiktor.v2whitelist.handler.MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, true)
            if (!isEnabled) {
                workManager.cancelUniqueWork(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
                Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: cancelled (disabled in settings)")
                return
            }

            val request = buildRequest(context)

            // CANCEL_AND_REENQUEUE: применяем новые настройки немедленно.
            workManager.enqueueUniquePeriodicWork(
                AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request
            )
            Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: rescheduled with new settings")
        }

        private fun buildRequest(context: Context): androidx.work.PeriodicWorkRequest {
            val intervalStr = com.kiktor.v2whitelist.handler.MmkvManager.decodeSettingsString(
                AppConfig.SUBSCRIPTION_AUTO_UPDATE_INTERVAL,
                AppConfig.SUBSCRIPTION_DEFAULT_UPDATE_INTERVAL
            )
            val intervalMinutes = intervalStr?.toLongOrNull() ?: 60L
            val finalInterval = if (intervalMinutes < 15) 15L else intervalMinutes

            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            return PeriodicWorkRequestBuilder<SubscriptionUpdaterWorker>(
                finalInterval, TimeUnit.MINUTES,
                // Flex-период: задача может запуститься в любой момент внутри последних 5 минут интервала.
                // Это помогает Android планировать задачу батарейно-эффективно.
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build() // Убираем setBackoffCriteria — retry больше не используется
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
