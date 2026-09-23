package com.itsazni.notificationforwarder.worker

import android.content.Context
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.itsazni.notificationforwarder.data.NotificationRepository
import com.itsazni.notificationforwarder.network.WebhookClient
import com.itsazni.notificationforwarder.settings.AuthMode
import com.itsazni.notificationforwarder.settings.SettingsStore

class QueueWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    private val repository = NotificationRepository(appContext)
    private val settings = SettingsStore(appContext)
    private val webhookClient = WebhookClient()

    override suspend fun doWork(): Result {
        // Recover queue items left in SENDING after a worker interruption/crash.
        repository.recoverStaleSending(System.currentTimeMillis() - 5 * 60 * 1000L)

        val config = settings.readAll()
        if (!config.forwardingEnabled || config.webhookUrl.isBlank()) {
            return Result.success()
        }

        val items = repository.getPending(config.batchSize)
        if (items.isEmpty()) {
            return Result.success()
        }

        repository.markSending(items.map { it.id })
        val deviceId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"

        val headers = buildHeaders(config.authMode, config.bearerToken, settings.parseHeaders())
        val queryParams = settings.parseQueryParams()

        var shouldRetry = false
        items.forEach { item ->
            try {
                val result = webhookClient.send(
                    url = config.webhookUrl,
                    method = config.webhookMethod,
                    headers = headers,
                    queryParams = queryParams,
                    payloadTemplate = config.payloadTemplateRaw,
                    item = item,
                    deviceId = deviceId
                )
                if (result.success) {
                    repository.markSent(item.id)
                } else {
                    val attempt = item.attemptCount + 1
                    repository.markFailure(
                        id = item.id,
                        attemptCount = if (result.isPermanentFailure) config.maxRetries else attempt,
                        maxRetry = config.maxRetries,
                        lastError = result.message
                    )
                    if (!result.isPermanentFailure) {
                        shouldRetry = true
                    }
                }
            } catch (e: Exception) {
                val attempt = item.attemptCount + 1
                repository.markFailure(
                    id = item.id,
                    attemptCount = attempt,
                    maxRetry = config.maxRetries,
                    lastError = e.message ?: e.javaClass.simpleName
                )
                shouldRetry = true
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    private fun buildHeaders(
        authMode: AuthMode,
        bearerToken: String,
        customHeaders: Map<String, String>
    ): Map<String, String> {
        val finalHeaders = linkedMapOf("Content-Type" to "application/json")
        if (authMode == AuthMode.BEARER && bearerToken.isNotBlank()) {
            finalHeaders["Authorization"] = "Bearer $bearerToken"
        }
        finalHeaders.putAll(customHeaders)
        return finalHeaders
    }
}
