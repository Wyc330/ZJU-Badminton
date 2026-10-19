package com.zju.courtgrab.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs

/**
 * 开机 / 应用更新 / 快速重启后，把每天的闹钟重新排上。
 * （AlarmManager 里的闹钟在重启后会丢失，这是必须的。）
 */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "BootRx"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Logs.init(context)
        val action = intent?.action
        Logs.i(TAG, "收到系统事件：$action")
        val prefs = Prefs.get(context)
        if (prefs.enabled) {
            AlarmScheduler.schedule(context, prefs)
        } else {
            Logs.i(TAG, "每日自动未启用，不排闹钟")
        }
    }
}
