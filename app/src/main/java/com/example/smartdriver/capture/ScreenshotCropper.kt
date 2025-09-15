package com.example.smartdriver.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Corta bitmaps removendo barras do sistema/cutouts e aplica trims extra
 * percentuais em cada margem para recentrar o conteúdo.
 *
 * Defaults: bottom=5%, top=2%, left=1%, right=1%.
 */
object ScreenshotCropper {
    private const val TAG = "SD.ScreenshotCropper"

    // Defaults pedidos
    private const val DEFAULT_EXTRA_BOTTOM_TRIM_PERCENT = 0.05f
    private const val DEFAULT_EXTRA_TOP_TRIM_PERCENT = 0.02f
    private const val DEFAULT_EXTRA_LEFT_TRIM_PERCENT = 0.01f
    private const val DEFAULT_EXTRA_RIGHT_TRIM_PERCENT = 0.01f

    /**
     * Mantém a assinatura original. Aplica por omissão:
     * - 5% no fundo, 2% no topo, 1% em cada lado.
     */
    @JvmStatic
    fun cropToSystemBarsSafeArea(context: Context, source: Bitmap, extraMarginDp: Float = 0f): Bitmap {
        return cropToSystemBarsSafeArea(
            context = context,
            source = source,
            extraMarginDp = extraMarginDp,
            extraBottomTrimPercent = DEFAULT_EXTRA_BOTTOM_TRIM_PERCENT,
            extraTopTrimPercent = DEFAULT_EXTRA_TOP_TRIM_PERCENT,
            extraLeftTrimPercent = DEFAULT_EXTRA_LEFT_TRIM_PERCENT,
            extraRightTrimPercent = DEFAULT_EXTRA_RIGHT_TRIM_PERCENT
        )
    }

    /**
     * Versão configurável com percentagens individuais por margem.
     * @param extraBottomTrimPercent fração da ALTURA a cortar no fundo (0.0..0.25 recomendado)
     * @param extraTopTrimPercent fração da ALTURA a cortar no topo
     * @param extraLeftTrimPercent fração da LARGURA a cortar à esquerda
     * @param extraRightTrimPercent fração da LARGURA a cortar à direita
     */
    @JvmStatic
    fun cropToSystemBarsSafeArea(
        context: Context,
        source: Bitmap,
        extraMarginDp: Float,
        extraBottomTrimPercent: Float = DEFAULT_EXTRA_BOTTOM_TRIM_PERCENT,
        extraTopTrimPercent: Float = DEFAULT_EXTRA_TOP_TRIM_PERCENT,
        extraLeftTrimPercent: Float = DEFAULT_EXTRA_LEFT_TRIM_PERCENT,
        extraRightTrimPercent: Float = DEFAULT_EXTRA_RIGHT_TRIM_PERCENT
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val extraMargin = (extraMarginDp * density).toInt().coerceAtLeast(0)

        var leftInset = 0
        var topInset = 0
        var rightInset = 0
        var bottomInset = 0

        try {
            val wm = context.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = wm.currentWindowMetrics
                val wi = metrics.windowInsets
                val mask = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val insets = wi.getInsetsIgnoringVisibility(mask)
                leftInset = insets.left
                topInset = insets.top
                rightInset = insets.right
                bottomInset = insets.bottom
            } else {
                // Fallback (pré-R)
                topInset = getDimenPx(context, "status_bar_height")
                bottomInset = getNavBarHeightCompat(context)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao obter WindowInsets: ${t.message}")
        }

        // Base: remover barras + margem extra
        val baseLeft   = (leftInset + extraMargin).coerceAtLeast(0)
        val baseTop    = (topInset + extraMargin).coerceAtLeast(0)
        val baseRight  = (source.width - rightInset - extraMargin).coerceAtLeast(0)
        val baseBottom = (source.height - bottomInset - extraMargin).coerceAtLeast(0)

        // Trims adicionais (guardados a 25% por margem)
        val maxPercent = 0.25f
        val leftTrimPx   = (source.width  * extraLeftTrimPercent.coerceIn(0f, maxPercent)).roundToInt()
        val rightTrimPx  = (source.width  * extraRightTrimPercent.coerceIn(0f, maxPercent)).roundToInt()
        val topTrimPx    = (source.height * extraTopTrimPercent.coerceIn(0f, maxPercent)).roundToInt()
        val bottomTrimPx = (source.height * extraBottomTrimPercent.coerceIn(0f, maxPercent)).roundToInt()

        val left = (baseLeft + leftTrimPx).coerceAtLeast(0)
        val top = (baseTop + topTrimPx).coerceAtLeast(0)
        val right = (baseRight - rightTrimPx).coerceAtMost(source.width)
        val bottom = (baseBottom - bottomTrimPx).coerceAtLeast(0)

        val rect = Rect(
            left.coerceIn(0, source.width),
            top.coerceIn(0, source.height),
            right.coerceIn(0, source.width),
            bottom.coerceIn(0, source.height)
        )

        if (rect.width() <= 0 || rect.height() <= 0) {
            Log.w(TAG, "Retângulo de corte inválido $rect; a devolver original.")
            return source
        }

        return try {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao cortar bitmap: ${t.message}")
            source
        }
    }

    private fun getDimenPx(context: Context, name: String): Int {
        val resId = context.resources.getIdentifier(name, "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    private fun getNavBarHeightCompat(context: Context): Int {
        val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val hasNavId = context.resources.getIdentifier("config_showNavigationBar", "bool", "android")
        val hasNavBar = if (hasNavId > 0) context.resources.getBoolean(hasNavId) else true
        return if (hasNavBar && resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }
}
