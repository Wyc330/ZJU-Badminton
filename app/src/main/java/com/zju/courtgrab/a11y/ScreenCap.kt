package com.zju.courtgrab.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Rgb
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 截图取色。
 *
 * 为什么需要它：「浙大体艺」的时段表是纯色块（空闲绿 / 未支付黄 / 已预约红 / 已选择蓝 / 不开放灰），
 * 无障碍节点里没有文字也没有状态描述 —— 只有像素能告诉我们哪一格能约。
 *
 * 用 AccessibilityService.takeScreenshot（Android 11+），不需要额外权限，
 * 但目标 App 如果给页面加了 FLAG_SECURE 会失败（会返回 ERROR_TAKE_SCREENSHOT_SECURE_WINDOW）。
 */
class ScreenCap(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ScreenCap"
    }

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    suspend fun capture(): Bitmap? {
        if (!supported) {
            Logs.w(TAG, "系统版本低于 Android 11，不支持无障碍截图取色")
            return null
        }
        return captureApi30()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("DEPRECATION")
    private suspend fun captureApi30(): Bitmap? = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(service)
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val bitmap = runCatching {
                    val buffer = result.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    copy
                }.getOrElse {
                    Logs.w(TAG, "截图转 Bitmap 失败：${it.message}")
                    null
                }
                if (cont.isActive) cont.resume(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                Logs.w(TAG, "截图失败 code=$errorCode（SECURE_WINDOW=${AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW}）")
                if (cont.isActive) cont.resume(null)
            }
        }
        runCatching {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        }.onFailure {
            Logs.w(TAG, "takeScreenshot 调用异常：${it.message}")
            if (cont.isActive) cont.resume(null)
        }
    }

    /** 取 (x,y) 附近 r 像素内出现次数最多的颜色（避开文字/边框干扰） */
    fun sampleRegion(bitmap: Bitmap, x: Int, y: Int, r: Int = 5): Rgb? {
        if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) return null
        val counts = HashMap<Int, Int>()
        var best = -1
        var bestCount = 0
        for (dx in -r..r) {
            for (dy in -r..r) {
                val px = x + dx
                val py = y + dy
                if (px < 0 || py < 0 || px >= bitmap.width || py >= bitmap.height) continue
                val c = runCatching { bitmap.getPixel(px, py) }.getOrNull() ?: continue
                // 量化到 8 的倍数，抵抗抗锯齿噪点
                val q = ((c shr 16) and 0xFF) / 8 * 8 * 65536 +
                    ((c shr 8) and 0xFF) / 8 * 8 * 256 +
                    (c and 0xFF) / 8 * 8
                val n = (counts[q] ?: 0) + 1
                counts[q] = n
                if (n > bestCount) {
                    bestCount = n
                    best = q
                }
            }
        }
        if (best < 0) return null
        return Rgb((best shr 16) and 0xFF, (best shr 8) and 0xFF, best and 0xFF)
    }

    fun recycle(bitmap: Bitmap?) {
        runCatching { if (bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }
}
