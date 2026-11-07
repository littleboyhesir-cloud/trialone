package com.kevv.stayfree

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Notifier {
    private const val CHANNEL = "progress"

    fun post(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Progress & motivation", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Lock screen shows only a neutral message (private).
        val publicVersion = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("StayFree").setContentText("Tap to open").build()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        try { nm.notify(id, n) } catch (e: SecurityException) { }
    }
}

object Messages {
    private const val TROPHY = "\uD83C\uDFC6"
    private const val MUSCLE = "\uD83D\uDCAA"

    fun progress(h: Habit, hour: Int, prefs: SharedPreferences): Pair<String, String> {
        val elapsed = (System.currentTimeMillis() - h.startTime).coerceAtLeast(0)
        val days = elapsed / 86_400_000L
        val ms = milestonesFor(h.category)
        val reached = ms.count { it.hours * 3_600_000 <= elapsed }
        val key = "ms_${h.id}_${h.startTime}"
        val last = prefs.getInt(key, Int.MIN_VALUE)
        prefs.edit().putInt(key, reached).apply()
        if (last != Int.MIN_VALUE && reached > last) {
            val m = ms[reached - 1]
            return "$TROPHY ${m.title} free!" to "${m.desc} Keep going."
        }
        val next = ms.firstOrNull { it.hours * 3_600_000 > elapsed }
        val nextTxt = next?.let { "Next milestone: ${it.title} (in ${fmtShort((it.hours * 3_600_000).toLong() - elapsed)})." }
            ?: "You've reached every milestone!"
        val label = if (days >= 1) "Day $days $MUSCLE" else "First hours $MUSCLE"
        return when {
            hour < 12 -> label to "Good morning. ${motivations.random()}"
            hour < 17 -> label to ((if (h.costPerUnit > 0)
                "You've saved ${money(elapsed / 86_400_000.0 * h.unitsPerDay * h.costPerUnit)} so far. " else "") + nextTxt)
            hour < 22 -> label to "$nextTxt ${motivations.random()}"
            else -> "$label Stay strong tonight" to
                "Late nights are the danger zone. Charge your phone outside the bedroom and ride out any urge for 10 minutes. You have $days day(s) to protect."
        }
    }
}

class ProgressWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val hour = inputData.getInt("hour", 8)
        val prefs = ctx.getSharedPreferences("cache", Context.MODE_PRIVATE)
        val habits = AppDb.get(ctx).dao().habitsOnce()
        val h = habits.firstOrNull { it.id == prefs.getLong("selected", -1L) } ?: habits.firstOrNull()
            ?: return Result.success()
        val (title, text) = Messages.progress(h, hour, prefs)
        Notifier.post(ctx, 1000 + hour, title, text)
        return Result.success()
    }
}

object Reminders {
    val slots = listOf(8 to "Morning 8:00", 13 to "Midday 13:00", 20 to "Evening 20:00", 23 to "Late night 23:00")

    private fun delayUntil(hour: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis - System.currentTimeMillis()
    }

    fun apply(ctx: Context, hours: Set<Int>) {
        val wm = WorkManager.getInstance(ctx)
        slots.forEach { (h, _) ->
            val name = "progress_$h"
            if (h in hours) {
                val req = PeriodicWorkRequestBuilder<ProgressWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delayUntil(h), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("hour" to h))
                    .build()
                wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
            } else wm.cancelUniqueWork(name)
        }
    }
}
