package ru.portfolio.watch

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Self-rearming, local wake-up alarms. A periodic WorkManager job is also retained
 * as a backup. Android Doze and OEM restrictions can still delay any job.
 */
object PriorityWake {
    const val CHECK_ACTION = "ru.portfolio.watch.PRIORITY_CHECK"
    private const val REQUEST_CODE = 781662
    private const val PREFS = "priority_wake_health"
    private const val ONE_TIME_NAME = "portfolio-watch-wake"
    private fun settings(context: Context) =
        context.getSharedPreferences("monitor_settings", Context.MODE_PRIVATE)
    private fun health(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun alarm(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm(context).canScheduleExactAlarms()

    fun batteryUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun powerSaveActive(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun operation(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_CODE,
            Intent(context, PriorityWakeReceiver::class.java).setAction(CHECK_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun cancel(context: Context) {
        alarm(context).cancel(operation(context))
        health(context).edit().putLong("nextDue", 0L).apply()
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_NAME)
    }

    /** Called after each alarm BEFORE network work. All clock values are local. */
    fun scheduleNext(context: Context) {
        if (!settings(context).getBoolean("enabled", false)) return
        val minutes = settings(context).getLong("intervalMinutes", 15).coerceIn(15L, 10080L)
        val intervalMs = TimeUnit.MINUTES.toMillis(minutes)
        val elapsedTarget = SystemClock.elapsedRealtime() + intervalMs
        val wake = operation(context)
        var exact = exactAllowed(context)
        try {
            if (exact) alarm(context).setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTarget, wake)
            else alarm(context).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTarget, wake)
        } catch (_: SecurityException) {
            exact = false
            alarm(context).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTarget, wake)
        }
        health(context).edit().putLong("nextDue", System.currentTimeMillis() + intervalMs)
            .putBoolean("scheduledExact", exact).apply()
    }

    /** Re-arm if the user grants/revokes exact permission or boot cleared alarms. */
    fun ensureConfigured(context: Context) {
        if (!settings(context).getBoolean("enabled", false)) return
        val h = health(context)
        val due = h.getLong("nextDue", 0L)
        if (due == 0L || due < System.currentTimeMillis() - 3L * 60000L ||
            h.getBoolean("scheduledExact", false) != exactAllowed(context)) scheduleNext(context)
    }

    fun fire(context: Context) {
        if (!settings(context).getBoolean("enabled", false)) return
        health(context).edit().putLong("lastWake", System.currentTimeMillis()).apply()
        try { scheduleNext(context) }
        catch (e: Exception) { failure(context, "Перезапуск будильника: " + (e.message ?: "ошибка")) }
        try {
            val work = OneTimeWorkRequestBuilder<MonitorWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_NAME, ExistingWorkPolicy.KEEP, work)
        } catch (e: Exception) {
            failure(context, "Задача проверки: " + (e.message ?: "ошибка"))
        }
    }

    fun attempted(context: Context) {
        health(context).edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
    }
    fun succeeded(context: Context) {
        health(context).edit().putLong("lastSuccess", System.currentTimeMillis())
            .putString("lastFailure", "").apply()
    }
    fun failure(context: Context, detail: String) {
        health(context).edit().putLong("lastFailureAt", System.currentTimeMillis())
            .putString("lastFailure", detail.take(180)).apply()
    }
    fun status(context: Context): WakeStatus {
        val h = health(context)
        return WakeStatus(
            exactAllowed(context), batteryUnrestricted(context),
            notificationsAllowed(context), powerSaveActive(context),
            h.getBoolean("scheduledExact", false), h.getLong("nextDue", 0L),
            h.getLong("lastWake", 0L), h.getLong("lastAttempt", 0L),
            h.getLong("lastSuccess", 0L), h.getString("lastFailure", "").orEmpty())
    }
}

data class WakeStatus(
    val exactAllowed: Boolean, val batteryUnrestricted: Boolean,
    val notificationsAllowed: Boolean, val powerSaveActive: Boolean,
    val scheduledExact: Boolean, val nextDue: Long, val lastWake: Long,
    val lastAttempt: Long, val lastSuccess: Long, val lastFailure: String
)

class PriorityWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val settings = context.getSharedPreferences("monitor_settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean("enabled", false)) return
        when (intent?.action) {
            PriorityWake.CHECK_ACTION -> PriorityWake.fire(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                try { PriorityWake.scheduleNext(context) }
                catch (e: Exception) {
                    PriorityWake.failure(context, "Восстановление: " + (e.message ?: "ошибка"))
                }
            }
        }
    }
}
