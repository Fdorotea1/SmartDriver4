package com.example.smartdriver.map

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Interface simples para fornecedores de rotas.
 * Devolve a sequência de pontos (GeoPoint) que compõe a rota,
 * ou null se falhar (caso em que podes desenhar linha reta).
 */
interface RouteProvider {
    fun getRoute(pickup: GeoPoint, dest: GeoPoint): List<GeoPoint>?
}

/**
 * Implementação baseada no OSRM público (sem chave).
 * Doc: https://project-osrm.org/
 *
 * Nota: serviço público com limites; bom para protótipo.
 * Em produção considera instância própria (OSRM/Valhalla) ou serviço com chave.
 */
class OSRMRouteProvider(
    private val baseUrl: String = "https://router.project-osrm.org",
    private val userAgent: String = "SmartDriver"
) : RouteProvider {

    override fun getRoute(pickup: GeoPoint, dest: GeoPoint): List<GeoPoint>? {
        // OSRM espera lon,lat (atenção à ordem!)
        val urlStr = "$baseUrl/route/v1/driving/" +
                "${lonLat(pickup)};${lonLat(dest)}" +
                "?overview=full&geometries=geojson&alternatives=false"

        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }

        return conn.useAndRead { code, body ->
            if (code != HttpURLConnection.HTTP_OK || body.isNullOrBlank()) return@useAndRead null

            val root = JSONObject(body)
            if (root.optString("code") != "Ok") return@useAndRead null

            val routes = root.optJSONArray("routes") ?: return@useAndRead null
            if (routes.length() == 0) return@useAndRead null

            val geometry = routes.getJSONObject(0).optJSONObject("geometry")
                ?: return@useAndRead null

            val coords = geometry.optJSONArray("coordinates")
                ?: return@useAndRead null

            val out = ArrayList<GeoPoint>(coords.length())
            for (i in 0 until coords.length()) {
                val pair = coords.getJSONArray(i)
                val lon = pair.getDouble(0)
                val lat = pair.getDouble(1)
                out.add(GeoPoint(lat, lon))
            }
            out
        }
    }

    private fun lonLat(p: GeoPoint): String = "${p.longitude},${p.latitude}"

    /**
     * Executa a ligação, lê todo o corpo como UTF-8 e devolve o resultado do bloco.
     * Se houver exceção, devolve null. Fecha sempre a ligação no finally.
     */
    private inline fun HttpsURLConnection.useAndRead(
        block: (code: Int, body: String?) -> List<GeoPoint>?
    ): List<GeoPoint>? {
        return try {
            connect()
            val code = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            block(code, body)
        } catch (_: Exception) {
            null
        } finally {
            disconnect()
        }
    }
}
