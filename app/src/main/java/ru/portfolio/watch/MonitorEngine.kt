package ru.portfolio.watch

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
        require(minutes in 15..10080) { "Интервал Android: от 15 минут до 7 суток." }
        PortfolioStore(context).setSchedule(enabled, minutes)
        val work = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            PriorityWake.cancel(context)
            work.cancelUniqueWork("portfolio-watch")
            return "Фоновый мониторинг отключён"
        }
        // Persisted backup if alarms are restricted, deferred or lost.
        val backup = PeriodicWorkRequestBuilder<MonitorWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork("portfolio-watch",
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, backup)
        PriorityWake.scheduleNext(context)
        return if (PriorityWake.exactAllowed(context))
            "Пробуждение каждые $minutes мин + резервная проверка включены. Время запуска зависит от Android."
        else
            "Пробуждение и резерв включены. Разреши точные будильники в настройках для более строгого расписания."
    }
}

class MonitorWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pref = applicationContext.getSharedPreferences("monitor_settings", Context.MODE_PRIVATE)
        if (!pref.getBoolean("enabled", false)) return Result.success()
        val interval = pref.getLong("intervalMinutes", 15L)
        val previous = PortfolioStore(applicationContext).readState().lastChecked
        if (BackgroundPolicy.skipRecentSuccess(System.currentTimeMillis(), previous, interval))
            return Result.success()
        PriorityWake.attempted(applicationContext)
        val outcome = withTimeoutOrNull(105000L) {
            suspendCancellableCoroutine<kotlin.Result<CheckOutcome>> { continuation ->
                MonitorEngine.run(applicationContext) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        } ?: run {
            PriorityWake.failure(applicationContext, "Истекло время проверки")
            return Result.retry()
        }
        val completed = outcome.getOrElse { problem ->
            if (problem.message == "Проверка уже выполняется") return Result.success()
            PriorityWake.failure(applicationContext,
                problem.message ?: "Не удалось проверить публичный портфель")
            return Result.retry()
        }
        PriorityWake.succeeded(applicationContext)
        if (completed.changes.isNotEmpty()) Notice.show(applicationContext, completed.changes)
        return Result.success()
    }
}
