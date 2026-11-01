package com.betterlife.liftlog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.graphics.PathParser
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** Streak widget (2×2): flame, day streak, workouts this week. Tap opens Today. */
class StreakWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) LiftlogWidgets.updateStreak(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        LiftlogWidgets.updateStreak(context, manager, id)
    }
}

/** Week widget (4×2): sets per day this week, workouts vs goal, and a Start workout button. */
class WeekWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) LiftlogWidgets.updateWeek(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        LiftlogWidgets.updateWeek(context, manager, id)
    }
}

object LiftlogWidgets {
    private const val BG = 0xFF0B1013.toInt()
    private const val TILE = 0xFF151C20.toInt()
    private const val BLUE = 0xFF0093E7.toInt()
    private const val ORANGE = 0xFFFF8A2A.toInt()
    private const val INK = 0xFFF2F4F5.toInt()
    private const val MUTED = 0xFF7D8A93.toInt()
    private const val DIM = 0xFF2A3439.toInt()

    private val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    /** Summary pushed by the web app; see pushWidget() in index.html. */
    fun save(ctx: Context, json: String) {
        ctx.getSharedPreferences("widget", Context.MODE_PRIVATE).edit().putString("data", json).apply()
        refreshAll(ctx)
    }

    private fun load(ctx: Context): JSONObject? = try {
        ctx.getSharedPreferences("widget", Context.MODE_PRIVATE).getString("data", null)?.let { JSONObject(it) }
    } catch (_: Throwable) {
        null
    }

    fun refreshAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        for (id in m.getAppWidgetIds(ComponentName(ctx, StreakWidget::class.java))) updateStreak(ctx, m, id)
        for (id in m.getAppWidgetIds(ComponentName(ctx, WeekWidget::class.java))) updateWeek(ctx, m, id)
    }

    private fun open(ctx: Context, hash: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("liftlog://open/$hash")
            putExtra(MainActivity.EXTRA_HASH, hash)
        }
        return PendingIntent.getActivity(ctx, hash.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun sizePx(ctx: Context, m: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o = m.getAppWidgetOptions(id)
        val density = ctx.resources.displayMetrics.density
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: defW
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: defH
        val scale = min(1f, 900f / (max(wDp, hDp) * density))
        return (wDp * density * scale).toInt().coerceAtLeast(80) to (hDp * density * scale).toInt().coerceAtLeast(80)
    }

    private fun paint(color: Int, size: Float = 0f, tf: Typeface = bold, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = tf; textAlign = align }

    private fun background(c: Canvas, w: Int, h: Int) {
        val r = min(w, h) * 0.11f
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint(BG))
    }

    /** Is the stored summary from today? After midnight the week strip would be stale. */
    private fun fresh(d: JSONObject): Boolean = d.optString("date") == LocalDate.now().toString()

    // Material "local_fire_department" (Apache 2.0), 24×24 viewport.
    private val FLAME = listOf(
        "M12,12.9l-2.13,2.09C9.31,15.55 9,16.28 9,17.06C9,18.68 10.35,20 12,20s3,-1.32 3,-2.94c0,-0.78 -0.31,-1.52 -0.87,-2.07L12,12.9z",
        "M16,6l-0.44,0.55C14.38,8.02 12,7.19 12,5.3V2c0,0 -8,4 -8,11c0,2.92 1.56,5.47 3.89,6.86C7.33,19.07 7,18.1 7,17.06c0,-1.32 0.52,-2.56 1.47,-3.5L12,10.1l3.53,3.47c0.95,0.93 1.47,2.17 1.47,3.5c0,1.02 -0.31,1.96 -0.85,2.75c1.89,-1.15 3.29,-3.06 3.71,-5.3C20.52,10.97 18.79,7.62 16,6z",
    ).map { PathParser.createPathFromPathData(it) }

    private fun flame(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        val m = android.graphics.Matrix().apply { setScale(s, s); postTranslate(cx - 12f * s, cy - 12f * s) }
        val p = paint(color)
        for (src in FLAME) c.drawPath(Path(src).apply { transform(m) }, p)
    }

    fun updateStreak(ctx: Context, m: AppWidgetManager, id: Int) {
        val (w, h) = sizePx(ctx, m, id, 160, 160)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        background(c, w, h)
        val u = min(w, h).toFloat()
        val d = load(ctx)
        if (d == null) {
            c.drawText("Open Liftlog", w / 2f, h / 2f, paint(MUTED, u * 0.1f, align = Paint.Align.CENTER))
        } else {
            val streak = d.optInt("streak")
            val active = streak > 0
            flame(c, w / 2f, h * 0.24f, u * 0.24f, if (active) ORANGE else DIM)
            c.drawText(streak.toString(), w / 2f, h * 0.60f, paint(INK, u * 0.30f, align = Paint.Align.CENTER))
            c.drawText("DAY STREAK", w / 2f, h * 0.72f, paint(MUTED, u * 0.085f, medium, Paint.Align.CENTER))
            // Workouts this week as dots: filled up to the count, outlined up to the goal.
            val week = d.optJSONArray("week")
            val thisWeek = week != null && (0 until week.length()).any { week.optJSONObject(it)?.optString("d") == LocalDate.now().toString() }
            val wo = if (thisWeek) d.optInt("wo") else 0
            val goal = d.optInt("goal", 4).coerceIn(1, 7)
            val r = u * 0.03f
            val gap = u * 0.1f
            val y = h * 0.86f
            val x0 = w / 2f - (goal - 1) * gap / 2f
            for (i in 0 until goal) {
                val x = x0 + i * gap
                if (i < wo) c.drawCircle(x, y, r, paint(BLUE))
                else c.drawCircle(x, y, r * 0.9f, paint(DIM).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.5f })
            }
        }
        val views = RemoteViews(ctx.packageName, R.layout.widget_image).apply {
            setImageViewBitmap(R.id.widget_image, bmp)
            setOnClickPendingIntent(R.id.widget_image, open(ctx, "today"))
        }
        m.updateAppWidget(id, views)
    }

    fun updateWeek(ctx: Context, m: AppWidgetManager, id: Int) {
        // The picture fills only the area above the button (widget minus padding, button and its
        // margins), drawn at that exact size so nothing gets stretched.
        val o = m.getAppWidgetOptions(id)
        val density = ctx.resources.displayMetrics.density
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 300
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 150
        val scale = min(density, 900f / wDp)
        val w = ((wDp - 8) * scale).toInt().coerceAtLeast(120)
        val h = ((hDp - 8 - 40 - 12) * scale).toInt().coerceAtLeast(60)
        val dp = scale
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = load(ctx)
        val padX = 16f * dp
        val padTop = 12f * dp
        if (d == null) {
            c.drawText("Open Liftlog to set up this widget", w / 2f, h / 2f, paint(MUTED, 14f * dp, medium, Paint.Align.CENTER))
        } else {
            val goal = d.optInt("goal", 4)
            val today = LocalDate.now().toString()
            val week = d.optJSONArray("week")
            val days = (0 until (week?.length() ?: 0)).mapNotNull { week!!.optJSONObject(it) }
            val sameWeek = days.any { it.optString("d") == today }
            val wo = if (sameWeek) d.optInt("wo") else 0
            val streak = d.optInt("streak")

            // Header — left: "THIS WEEK" over "2 / 3 workouts"; right: flame + streak over "day streak".
            val label = paint(MUTED, 11f * dp, medium).apply { letterSpacing = 0.08f }
            val big = paint(INK, 24f * dp, bold)
            val small = paint(MUTED, 13f * dp, medium)
            val y1 = padTop + 11f * dp
            val y2 = y1 + 26f * dp
            c.drawText("THIS WEEK", padX, y1, label)
            val count = "$wo/$goal"
            c.drawText(count, padX, y2, big)
            c.drawText("workouts", padX + big.measureText(count) + 6f * dp, y2, small)

            val labelR = paint(MUTED, 11f * dp, medium, Paint.Align.RIGHT).apply { letterSpacing = 0.08f }
            c.drawText("STREAK", w - padX, y1, labelR)
            val bigR = paint(INK, 24f * dp, bold, Paint.Align.RIGHT)
            c.drawText(streak.toString(), w - padX, y2, bigR)
            val fs = 20f * dp
            flame(c, w - padX - bigR.measureText(streak.toString()) - 4f * dp - fs / 2, y2 - 9f * dp, fs, if (streak > 0) ORANGE else DIM)

            // Bars: sets per day, Sunday to Saturday, fixed width so they never look stretched.
            val dayLabel = 18f * dp
            val top = y2 + 14f * dp
            val bottom = h - dayLabel - 4f * dp
            if (bottom - top > 12f * dp) {
                val slot = (w - 2 * padX) / 7f
                val barW = min(slot * 0.5f, 18f * dp)
                val maxSets = max(1, days.maxOfOrNull { it.optInt("sets") } ?: 1)
                val labels = listOf("S", "M", "T", "W", "T", "F", "S")
                for (i in 0 until 7) {
                    val day = days.getOrNull(i)
                    val ds = day?.optString("d") ?: ""
                    val sets = if (sameWeek) day?.optInt("sets") ?: 0 else 0
                    val cx = padX + slot * (i + 0.5f)
                    val isToday = ds == today
                    val future = sameWeek && ds > today
                    val r = barW / 2
                    if (!future) c.drawRoundRect(RectF(cx - r, top, cx + r, bottom), r, r, paint(TILE))
                    else c.drawCircle(cx, bottom - r, r * 0.35f, paint(DIM))
                    if (sets > 0) {
                        val hh = max(barW, (bottom - top) * sets / maxSets)
                        c.drawRoundRect(RectF(cx - r, bottom - hh, cx + r, bottom), r, r, paint(BLUE))
                    }
                    c.drawText(labels[i], cx, h - 4f * dp, paint(if (isToday) INK else MUTED, 12f * dp, if (isToday) bold else medium, Paint.Align.CENTER))
                    if (isToday) c.drawCircle(cx, h - 1.5f * dp, 1.5f * dp, paint(BLUE))
                }
            }
        }
        val views = RemoteViews(ctx.packageName, R.layout.widget_week).apply {
            setImageViewBitmap(R.id.widget_image, bmp)
            setOnClickPendingIntent(R.id.widget_image, open(ctx, "today"))
            val todaySets = d?.optInt("todaySets") ?: 0
            setTextViewText(R.id.widget_button, if (todaySets > 0 && d != null && fresh(d)) "Continue · $todaySets sets" else "Start workout")
            setOnClickPendingIntent(R.id.widget_button, open(ctx, "log"))
        }
        m.updateAppWidget(id, views)
    }
}
