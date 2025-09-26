package com.example.smartdriver.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object PendingOfferStore {
    private const val TAG = "PendingOfferStore"
    private const val DIR_NAME = "offer_pending"
    private const val EXT = ".jpg"

    private const val DEFAULT_MAX_AGE_MS = 30 * 60 * 1000L
    private const val DEFAULT_MAX_ITEMS = 12

    private fun dir(ctx: Context): File {
        val d = File(ctx.cacheDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun signatureHash(signature: String): String {
        return Integer.toHexString(signature.hashCode())
    }

    private fun buildFile(ctx: Context, ts: Long, signature: String): File {
        val safeHash = signatureHash(signature)
        val name = String.format(Locale.US, "pending_%d_%s%s", ts, safeHash, EXT)
        return File(dir(ctx), name)
    }

    fun enqueue(ctx: Context, bitmap: Bitmap, signature: String) {
        try {
            cleanup(ctx)
            val ts = System.currentTimeMillis()
            val f = buildFile(ctx, ts, signature)
            FileOutputStream(f).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            Log.i(TAG, "enqueue: pendente guardada -> ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "enqueue: falha a guardar pendente: ${e.message}")
        }
    }

    fun promoteLatestToTrip(ctx: Context, tripId: String?): Boolean {
        try {
            val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(EXT, true) }?.toList().orEmpty()
            if (files.isEmpty()) return false
            val latest = files.maxByOrNull { it.lastModified() } ?: return false
            val bmp = BitmapFactory.decodeFile(latest.absolutePath) ?: return false
            val saved = TripMediaStorage.saveBitmapForTrip(ctx, bmp, tripId)
            bmp.recycle()
            if (saved != null) {
                TripScreenshotIndex.add(ctx, saved.absolutePath, saved.timestampMillis, tripId)
                latest.delete()
                Log.i(TAG, "promote: pendente promovida para trip=$tripId")
                return true
            }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "promoteLatestToTrip: erro: ${e.message}")
            return false
        }
    }

    fun cleanup(ctx: Context, maxAgeMs: Long = DEFAULT_MAX_AGE_MS, maxItems: Int = DEFAULT_MAX_ITEMS) {
        try {
            val list = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(EXT, true) }?.toMutableList() ?: return
            val now = System.currentTimeMillis()

            list.filter { now - it.lastModified() > maxAgeMs }.forEach { it.delete() }
            val remaining = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(EXT, true) }?.toMutableList() ?: return

            if (remaining.size > maxItems) {
                remaining.sortByDescending { it.lastModified() }
                val toDelete = remaining.drop(maxItems)
                toDelete.forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    fun clearAll(ctx: Context) {
        try {
            dir(ctx).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
