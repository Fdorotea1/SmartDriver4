package com.example.smartdriver.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object TripMediaStorage {
    private const val TAG = "TripMediaStorage"

    data class SavedPath(val absolutePath: String, val timestampMillis: Long)

    /**
     * Guarda uma screenshot da oferta numa pasta por tripId.
     * Se tripId for null, usa "unassigned".
     */
    fun saveBitmapForTrip(context: Context, bmp: Bitmap, tripId: String?): SavedPath? {
        return try {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            val root = File(baseDir, "SmartDriverTrips")
            if (!root.exists()) root.mkdirs()

            val id = tripId?.ifBlank { "unassigned" } ?: "unassigned"
            val tripDir = File(root, id).apply { if (!exists()) mkdirs() }

            val ts = System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyMMdd_HHmmss_SSS", Locale.US).format(Date(ts))
            val file = File(tripDir, "offer_$stamp.jpg")

            FileOutputStream(file).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
            }
            if (!bmp.isRecycled) bmp.recycle()

            SavedPath(file.absolutePath, ts)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha a guardar screenshot: ${t.message}", t)
            try { if (!bmp.isRecycled) bmp.recycle() } catch (_: Throwable) {}
            null
        }
    }
}
