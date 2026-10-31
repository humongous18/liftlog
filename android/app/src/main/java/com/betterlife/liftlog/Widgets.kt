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

    private fun flame(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        // Material "local_fire_department" outline, scaled from its 24×24 viewport.
        val s = size / 24f
        val p = Path()
        p.moveTo(16f, 6f); p.lineTo(15.56f, 6.55f)
        p.cubicTo(14.38f, 8.02f, 12f, 7.19f, 12f, 5.3f); p.lineTo(12f, 2f)
        p.cubicTo(12f, 2f, 4f, 6f, 4f, 13f)
        p.cubicTo(4f, 17.42f, 7.58f, 21f, 12f, 21f)
        p.cubicTo(16.42f, 21f, 20f, 17.42f, 20f, 13f)
        p.cubicTo(20f, 10.04f, 18.39f, 7.38f, 16f, 6f); p.close()
        val m = android.graphics.Matrix().apply { setScale(s, s); postTranslate(cx - 12f * s, cy - 12f * s) }
        p.transform(m)
        c.drawPath(p, paint(color))
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
        val (w, h) = sizePx(ctx, m, id, 300, 150)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        background(c, w, h)
        val u = h.toFloat()
        val d = load(ctx)
        val pad = u * 0.1f
        if (d == null) {
            c.drawText("Open Liftlog to set up this widget", w / 2f, h / 2f, paint(MUTED, u * 0.1f, align = Paint.Align.CENTER))
        } else {
            val goal = d.optInt("goal", 4)
            val today = LocalDate.now().toString()
            val week = d.optJSONArray("week")
            val days = (0 until (week?.length() ?: 0)).map { week!!.getJSONObject(it) }
            val sameWeek = days.any { it.optString("d") == today } && fresh(d)
            val wo = if (sameWeek) d.optInt("wo") else 0
            // Header: "This week 3/4" and the streak.
            c.drawText("THIS WEEK", pad, pad + u * 0.1f, paint(MUTED, u * 0.1f, medium))
            val hdr = paint(INK, u * 0.16f)
            c.drawText("$wo/$goal", pad, pad + u * 0.27f, hdr)
            val streak = d.optInt("streak")
            val sp = paint(INK, u * 0.13f, align = Paint.Align.RIGHT)
            val sx = w - pad
            c.drawText(streak.toString(), sx, pad + u * 0.25f, sp)
            flame(c, sx - sp.measureText(streak.toString()) - u * 0.09f, pad + u * 0.2f, u * 0.14f, if (streak > 0) ORANGE else DIM)

            // Bars: sets per day, Sunday to Saturday.
            val top = pad + u * 0.38f
            val bottom = h - pad - u * 0.14f
            val slot = (w - 2 * pad) / 7f
            val maxSets = max(1, days.maxOfOrNull { it.optInt("sets") } ?: 1)
            val barW = slot * 0.42f
            val labels = listOf("S", "M", "T", "W", "T", "F", "S")
            for (i in 0 until 7) {
                val day = days.getOrNull(i)
                val ds = day?.optString("d") ?: ""
                val sets = if (sameWeek) day?.optInt("sets") ?: 0 else 0
                val cx = pad + slot * (i + 0.5f)
                val future = sameWeek && ds > today
                val track = RectF(cx - barW / 2, top, cx + barW / 2, bottom)
                c.drawRoundRect(track, barW / 2, barW / 2, paint(if (future) 0x00000000 else TILE))
                if (sets > 0) {
                    val hh = max(barW, (bottom - top) * sets / maxSets)
                    c.drawRoundRect(RectF(cx - barW / 2, bottom - hh, cx + barW / 2, bottom), barW / 2, barW / 2, paint(BLUE))
                }
                val isToday = ds == today
                c.drawText(labels[i], cx, h - pad * 0.7f, paint(if (isToday) INK else MUTED, u * 0.09f, if (isToday) bold else medium, Paint.Align.CENTER))
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
