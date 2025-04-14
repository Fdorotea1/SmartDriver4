package com.example.smartdriver

import android.content.Intent
import android.os.Parcelable
import kotlinx.parcelize.Parcelize // Importar para usar a anotação @Parcelize

/**
 * Classe de dados simples para encapsular os dados da Media Projection
 * recebidos da Activity após o usuário conceder permissão.
 * Usa a anotação @Parcelize para gerar automaticamente o código Parcelable.
 *
 * @property resultCode O código de resultado retornado por onActivityResult.
 * @property data O Intent contendo os dados da projeção.
 */
@Parcelize // Gera automaticamente a implementação Parcelable
data class MediaProjectionData(
    val resultCode: Int,
    val data: Intent
) : Parcelable

// NOTA: Para usar @Parcelize, você pode precisar adicionar o plugin
// 'kotlin-parcelize' no build.gradle (Module :app) se ainda não estiver lá.
// Adicione `id("kotlin-parcelize")` dentro do bloco `plugins { ... }`
// e sincronize o projeto.