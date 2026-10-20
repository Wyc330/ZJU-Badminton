package com.zju.courtgrab.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zju.courtgrab.MainActivity
import com.zju.courtgrab.core.Logs

/**
 * 仅 debug 构建：允许通过 adb 广播触发调试动作，便于在真机上无人值守地跑流程。
 * adb shell am broadcast -n com.zju.courtgrab/.debug.DebugTriggerReceiver \
 *   -a com.zju.courtgrab.debug.TRIGGER --es action selfcheck|start|login|dryrun_on|dryrun_off
 */
class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action").orEmpty()
        Logs.i("DebugTrigger", "收到调试动作：$action")
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("debug_action", action)
        }
        runCatching { context.startActivity(launch) }
    }
}
