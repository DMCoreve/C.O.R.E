package com.dmcore.core.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Ejecuta las acciones que el backend devuelve en el campo "action" de /interact
 * (ver backend/llm.py). El backend decide QUÉ hacer; el teléfono es quien lo hace de
 * verdad, porque abrir una app o programar una alarma solo tiene sentido en el
 * dispositivo del usuario.
 */
object ActionExecutor {

    private const val TAG = "ActionExecutor"

    fun execute(context: Context, action: JSONObject?) {
        if (action == null) return

        val args = action.optJSONObject("args") ?: JSONObject()
        when (action.optString("name")) {
            "open_app" -> openApp(context, args.optString("app_name"))
            "set_reminder" -> scheduleReminder(context, args.optString("title"), args.optString("when_iso"))
            "start_dictation" -> ContextCompat.startForegroundService(context, Intent(context, DictationService::class.java))
            else -> Log.w(TAG, "Acción desconocida: ${action.optString("name")}")
        }
    }

    private fun openApp(context: Context, appName: String) {
        if (appName.isBlank()) return
        val target = appName.trim().lowercase()

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcherIntent, 0).firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            label.contains(target) || target.contains(label)
        }

        if (match == null) {
            Log.w(TAG, "No se encontró una app instalada que coincida con \"$appName\"")
            return
        }

        pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    private fun scheduleReminder(context: Context, title: String, whenIso: String) {
        if (title.isBlank() || whenIso.isBlank()) return

        val triggerMillis = try {
            LocalDateTime.parse(whenIso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo interpretar la fecha \"$whenIso\"", e)
            return
        }

        val intent = Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_TITLE, title)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            title.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        }
    }
}
