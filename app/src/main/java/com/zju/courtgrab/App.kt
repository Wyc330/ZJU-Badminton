package com.zju.courtgrab

import android.app.Application
import com.zju.courtgrab.core.ConfigRepo
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.util.Notify
import kotlin.concurrent.thread

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Logs.init(this)
        Notify.ensureChannels(this)
        // 读配置放后台线程，避免拖慢冷启动（抢场地时冷启动速度很关键）
        thread(name = "config-preload") {
            runCatching {
                val cfg = ConfigRepo.load(this)
                Logs.i("App", "配置已载入：目标包名=${cfg.targetPackage}")
            }.onFailure { Logs.e("App", "配置载入失败", it) }
        }
    }
}
