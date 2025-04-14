package com.example.smartdriver

import android.app.Activity
import android.content.Intent

/**
 * Objeto Singleton para armazenar os dados da Media Projection.
 * Substitui a data class Parcelable para acesso estático mais simples
 * a partir de diferentes componentes (Activity, Service).
 */
object MediaProjectionData {
    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile var resultData: Intent? = null

    fun clear() {
        resultCode = Activity.RESULT_CANCELED
        resultData = null
    }
}