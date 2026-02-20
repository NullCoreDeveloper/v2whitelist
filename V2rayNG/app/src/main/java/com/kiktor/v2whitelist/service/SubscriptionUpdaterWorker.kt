package com.kiktor.v2whitelist.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.handler.SmartConnectManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager задание для автообновления подписки раз в час.
 * Запускается в фоне даже когда приложение закрыто.
 */
class SubscriptionUpdaterWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: starting background subscription update")
        return try {
            SmartConnectManager.updateSubscription(applicationContext)
            Log.i(AppConfig.TAG, "SubscriptionUpdaterWorker: subscription updated successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "SubscriptionUpdaterWorker: failed to update subscription", e)
            Result.retry()
        }
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
