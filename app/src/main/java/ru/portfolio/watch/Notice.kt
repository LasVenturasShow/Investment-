package ru.portfolio.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Notice {
    private const val CHANNEL = "portfolio_changed"
    fun show(context: Context, changes: List<PortfolioChange>) {
        if (changes.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Изменения портфеля", NotificationManager.IMPORTANCE_HIGH))
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val summary = if (changes.size == 1) changes[0].label else "Обнаружено изменений: ${changes.size}"
        val details = changes.take(3).joinToString("; ") { it.label }
        @Suppress("DEPRECATION")
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(summary)
            .setContentText(details)
            .setStyle(android.app.Notification.BigTextStyle().bigText(details))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        manager.notify(781662, notification)
    }
}
