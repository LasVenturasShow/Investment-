package ru.portfolio.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object MonitorEngine {
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** Can be called from any thread. Callback is always delivered to the main thread. */
    fun run(context: Context, callback: (Result<CheckOutcome>) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            main.post { callback(Result.failure(IllegalStateException("Проверка уже выполняется"))) }
            return
        }
        main.post {
            try {
                Collector(context.applicationContext).collect { captured ->
                    if (captured.isFailure) {
                        busy.set(false)
                        callback(Result.failure(captured.exceptionOrNull() ?: IllegalStateException("Нет данных")))
                    } else {
                        thread(name = "portfolio-db-write") {
                            val outcome = runCatching { PortfolioStore(context).record(captured.getOrThrow()) }
                            busy.set(false)
                            main.post { callback(outcome) }
                        }
                    }
                }
            } catch (e: Exception) {
                busy.set(false)
                callback(Result.failure(e))
            }
        }
    }

    fun configure(context: Context, enabled: Boolean, minutes: Long): String {
        require(minutes in 15..10080) { "Фоновый интервал Android: от 15 до 10080 минут (7 суток)." }
        PortfolioStore(context).setSchedule(enabled, minutes)
        val work = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            work.cancelUniqueWork("portfolio-watch")
            return "Фоновый мониторинг отключён"
        }
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork("portfolio-watch", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        return "Фоновая проверка запланирована каждые $minutes мин (Android может откладывать запуск)"
    }
}

class MonitorWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = withTimeoutOrNull(105000) {
            suspendCancellableCoroutine<kotlin.Result<CheckOutcome>> { continuation ->
                MonitorEngine.run(applicationContext) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        } ?: return Result.retry()
        val value = outcome.getOrNull() ?: return Result.retry()
        if (value.changes.isNotEmpty()) Notice.show(applicationContext, value.changes)
        return Result.success()
    }
}
