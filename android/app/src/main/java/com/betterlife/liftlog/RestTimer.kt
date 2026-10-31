package com.betterlife.liftlog

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Rest timer outside the WebView: a live countdown notification while Liftlog is in the
 * background, and an exact alarm that buzzes "Rest over" even if the app was closed.
 */
object RestTimer {
    private const val CH_RUNNING = "rest_running"
    private const val CH_DONE = "rest_done"
    private const val ID_RUNNING = 1
    private const val ID_DONE = 2
    private val BUZZ = longArrayOf(0, 500, 250, 500)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("rest", Context.MODE_PRIVATE)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, "Rest countdown", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Time left on your rest while Liftlog is in the background"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Rest over", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Buzzes when it's time for your next set"
                enableVibration(true)
                vibrationPattern = BUZZ
            },
        )
    }

    /**
     * Called by the page whenever its timer changes. endMs = 0: cleared. endMs in the future: running.
     * endMs in the past: the rest just ended — notify now if Liftlog isn't on screen (the page's own
     * timer often notices before the alarm fires, and must not cancel it).
     */
    fun set(ctx: Context, endMs: Long, totalMs: Long, exercise: String) {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        p.edit().putLong("end", endMs).putLong("total", totalMs).putString("ex", exercise).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        when {
            endMs <= 0L -> {
                cancelAlarm(ctx)
                hideOngoing(ctx)
                nm.cancel(ID_DONE)
            }
            endMs > now -> {
                nm.cancel(ID_DONE)
                arm(ctx, endMs)
                if (!MainActivity.foreground) showOngoing(ctx) else hideOngoing(ctx)
            }
            else -> {
                hideOngoing(ctx)
                if (!MainActivity.foreground) notifyDone(ctx)
            }
        }
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, RestDoneReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun arm(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
        }
    }

    private fun cancelAlarm(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx))
    }

    private fun canPost(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun showOngoing(ctx: Context) {
        val p = prefs(ctx)
        val end = p.getLong("end", 0)
        val now = System.currentTimeMillis()
        if (end <= now || !canPost(ctx)) return
        val ex = p.getString("ex", "").orEmpty()
        val total = p.getLong("total", 0).coerceAtLeast(1)
        val n = Notification.Builder(ctx, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_barbell)
            .setContentTitle("Resting")
            .setContentText(if (ex.isNotBlank()) "Next: $ex" else "Rest timer")
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(end)
            .setShowWhen(true)
            .setProgress(1000, (((total - (end - now)).toFloat() / total) * 1000).toInt().coerceIn(0, 1000), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(end - now + 1000)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_RUNNING, n)
    }

    fun hideOngoing(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_RUNNING)
    }

    fun onAlarm(ctx: Context) {
        val end = prefs(ctx).getLong("end", 0)
        // Cleared, or moved later (the alarm for the new end is already armed).
        if (end == 0L || System.currentTimeMillis() < end - 2000) return
        hideOngoing(ctx)
        if (!MainActivity.foreground) notifyDone(ctx)
    }

    /** Posts "Rest over" once per rest (the alarm and the page may both report the end). */
    private fun notifyDone(ctx: Context) {
        val p = prefs(ctx)
        val end = p.getLong("end", 0)
        if (end == 0L || p.getLong("notified", 0) == end || !canPost(ctx)) return
        p.edit().putLong("notified", end).apply()
        val ex = p.getString("ex", "").orEmpty()
        val n = Notification.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_barbell)
            .setContentTitle("Rest over")
            .setContentText(if (ex.isNotBlank()) "Time for your next set of $ex." else "Time for your next set.")
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(10 * 60_000L)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
        // Buzz directly as well, in case the channel's vibration was turned off or the phone
        // only vibrates for some channels.
        try {
            val v = ctx.getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createWaveform(BUZZ, -1))
        } catch (_: Throwable) {
        }
    }
}

class RestDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RestTimer.createChannels(context)
        RestTimer.onAlarm(context)
    }
}
