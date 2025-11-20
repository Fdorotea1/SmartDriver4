package com.example.smartdriver.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.smartdriver.R
import com.example.smartdriver.overlay.OverlayView.BannerType
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class BannerManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private data class BannerReq(val text: String, val type: BannerType, val durationMs: Long)

    private val queue: ArrayDeque<BannerReq> = ArrayDeque()
    private var view: FrameLayout? = null
    private var isAdded = false
    private val isShowing = AtomicBoolean(false)

    private val lp: WindowManager.LayoutParams by lazy {
        val density = context.resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * density).roundToInt()
        }
    }

    fun show(text: String, type: BannerType, durationMs: Long = 3500L) {
        mainHandler.post {
            queue.add(BannerReq(text, type, durationMs))
            if (!isShowing.get()) drain()
        }
    }

    fun cancelAll() {
        mainHandler.post {
            queue.clear()
            removeCurrent(true)
        }
    }

    private fun drain() {
        if (isShowing.get() || queue.isEmpty()) return
        val req = queue.removeFirst()
        showInternal(req)
    }

    private fun showInternal(req: BannerReq) {
        isShowing.set(true)

        val density = context.resources.displayMetrics.density
        val paddingH = (14 * density).roundToInt()
        val paddingV = (10 * density).roundToInt()

        val tv = TextView(context).apply {
            text = req.text
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }

        val container = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, when (req.type) {
                BannerType.SUCCESS -> R.drawable.bg_banner_green
                BannerType.WARNING -> R.drawable.bg_banner_yellow

                BannerType.INFO    -> R.drawable.bg_banner_blue
            })
            setPadding(paddingH, paddingV, paddingH, paddingV)
            addView(tv)
            alpha = 0f
        }

        view = container

        try {
            wm.addView(container, lp)
            isAdded = true
        } catch (_: Exception) {
            isAdded = false
            view = null
            isShowing.set(false)
            drain()
            return
        }

        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 160 }
        container.startAnimation(fadeIn)
        container.alpha = 1f

        mainHandler.postDelayed({
            hideCurrent {
                isShowing.set(false)
                drain()
            }
        }, req.durationMs)
    }

    private fun hideCurrent(onHidden: () -> Unit) {
        val v = view ?: return onHidden()
        val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 160 }
        v.startAnimation(fadeOut)
        mainHandler.postDelayed({
            removeCurrent(false)
            onHidden()
        }, 160)
    }

    private fun removeCurrent(immediate: Boolean) {
        val v = view ?: return
        try {
            if (isAdded) wm.removeViewImmediate(v) else wm.removeView(v)
        } catch (_: Exception) {
        } finally {
            isAdded = false
            view = null
        }
    }
}
