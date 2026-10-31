package com.betterlife.liftlog

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exposed to the web app as `window.LiftlogAndroid`. Methods run on a WebView binder thread.
 * Only Liftlog's own bundled page is ever loaded in this WebView.
 */
class Bridge(private val activity: MainActivity) {

    companion object {
        const val HABITS_PACKAGE = "com.betterlife.habits"
        const val HABITS_ACTION = "com.betterlife.habits.action.EXTERNAL_COMPLETIONS"
        const val SYNC_PERMISSION = "com.betterlife.permission.SYNC"
        private const val RELEASES = "https://github.com/humongous18/Streaks/releases/latest"
    }

    private val ctx: Context get() = activity.applicationContext

    /**
     * Sends the recent workout days to Habits in the background. Habits answers with a result code
     * (1 synced, 2 no habit linked yet; 0 means Habits isn't installed) which is passed back to the
     * page as `__habitsResult(code, habitTitle)`.
     */
    @JavascriptInterface
    fun syncWorkouts(goal: Int, days: String) {
        val intent = Intent(HABITS_ACTION)
            .setPackage(HABITS_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("src", "liftlog")
            .putExtra("goal", goal)
            .putExtra("d", days)
        val result = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val code = resultCode
                val title = resultData ?: ""
                activity.js("window.__habitsResult && window.__habitsResult($code, ${JSONObject.quote(title)})")
            }
        }
        // receiverPermission: only a Habits build signed with our key can receive the workouts.
        ctx.sendOrderedBroadcast(intent, SYNC_PERMISSION, result, Handler(Looper.getMainLooper()), 0, null, null)
    }

    @JavascriptInterface
    fun saveFile(name: String, mime: String, text: String) {
        activity.runOnUiThread { activity.saveFile(name, mime, text) }
    }

    /** endMs = 0 clears the timer. */
    @JavascriptInterface
    fun restTimer(endMs: Double, totalMs: Double, exercise: String) {
        RestTimer.set(ctx, endMs.toLong(), totalMs.toLong(), exercise)
    }

    @JavascriptInterface
    fun vibrate(patternJson: String) {
        try {
            val a = JSONArray(patternJson)
            val timings = LongArray(a.length() + 1) { if (it == 0) 0L else a.optLong(it - 1) }
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createWaveform(timings, -1))
        } catch (_: Throwable) {
        }
    }

    @JavascriptInterface
    fun keepAwake(on: Boolean) {
        activity.runOnUiThread { activity.keepAwake(on) }
    }

    @JavascriptInterface
    fun notificationsEnabled(): Boolean =
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    @JavascriptInterface
    fun requestNotifications() {
        activity.runOnUiThread { activity.requestNotifications() }
    }

    /** Opens Habits' "choose a habit" screen with the workouts attached. */
    @JavascriptInterface
    fun openHabits(link: String) {
        val uri = Uri.parse(link)
        if (uri.scheme != "habits") return
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(HABITS_PACKAGE))
            } catch (_: Throwable) {
                activity.openExternal(Uri.parse(RELEASES))
            }
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        activity.runOnUiThread { activity.openExternal(uri) }
    }

    @JavascriptInterface
    fun platformVersion(): Int = Build.VERSION.SDK_INT
}
