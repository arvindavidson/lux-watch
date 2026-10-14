package com.luxwatch.gadget.noise

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Plain-HTTPS Muse API calls (outside the Noise channel):
 * GET /fetch_vms and POST /device_token/refresh.
 * Endpoints and semantics from PROTOCOL.md §2 and §8.
 */
class MuseApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    data class VmEntry(
        val vmId: String,
        val wsUrl: String,
        val authToken: String,
        val name: String,
        val default: Boolean,
    )

    data class TokenPair(val accessToken: String, val refreshToken: String)

    class ApiError(val status: Int, message: String) : RuntimeException("HTTP $status: $message")

    /** GET {apiBase}/fetch_vms. Throws ApiError(401) when the device token is rejected. */
    fun fetchVms(accessToken: String, apiBase: String = "https://api.muse.ai"): List<VmEntry> {
        val req = Request.Builder()
            .url("$apiBase/fetch_vms")
            .header("Authorization", "Bearer $accessToken")
            .header("X-API-Version", "1.0.0")
            .header("User-Agent", "luxwatch/0.1.0 (Wear OS)")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiError(resp.code, body.take(200))
            return parseVmList(body)
        }
    }

    /**
     * POST {apiBase}/device_token/refresh.
     * Per the SDK, the *refresh* token goes in the Authorization header
     * (prefixed hatch_refresh:), never the access token.
     */
    fun refreshToken(
        refreshToken: String,
        deviceId: String,
        sdkToken: String?,
        apiBase: String = "https://api.muse.ai",
    ): TokenPair {
        val bare = refreshToken.substringAfterLast(":")
        val json = buildString {
            append("{\"device_id\":\"").append(deviceId).append("\"")
            if (sdkToken != null) append(",\"sdk_token\":\"").append(sdkToken).append("\"")
            append("}")
        }
        val req = Request.Builder()
            .url("$apiBase/device_token/refresh")
            .header("Authorization", "Bearer hatch_refresh:$bare")
            .header("Content-Type", "application/json")
            .header("User-Agent", "luxwatch/0.1.0 (Wear OS)")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiError(resp.code, body.take(200))
            return parseTokenPair(body)
        }
    }

    // ---- minimal JSON parsing (no extra dependency) ----

    private fun parseVmList(body: String): List<VmEntry> {
        val out = mutableListOf<VmEntry>()
        // Find each {...} object inside "vm_list": [...]
        val listMatch = Regex("\"vm_list\"\\s*:\\s*\\[").find(body) ?: return out
        var i = listMatch.range.last + 1
        var depth = 1
        val start = i
        while (i < body.length && depth > 0) {
            when (body[i]) { '[' -> depth++; ']' -> depth-- }
            i++
        }
        val listBody = body.substring(start, i - 1)
        // Split top-level objects.
        var j = 0
        while (j < listBody.length) {
            val o = listBody.indexOf('{', j)
            if (o < 0) break
            var d = 0; var k = o
            do {
                when (listBody[k]) { '{' -> d++; '}' -> d-- }
                k++
            } while (d > 0 && k < listBody.length)
            val obj = listBody.substring(o, k)
            val url = strField(obj, "vm_ws_url") ?: strField(obj, "vm_url")
            val token = strField(obj, "vm_auth_token")
            if (url != null && token != null) {
                out.add(
                    VmEntry(
                        vmId = strField(obj, "vm_id") ?: strField(obj, "vm_name") ?: "",
                        wsUrl = url,
                        authToken = token,
                        name = strField(obj, "vm_name") ?: "",
                        default = obj.contains("\"default\"\\s*:\\s*true".toRegex()),
                    )
                )
            }
            j = k
        }
        return out
    }

    private fun parseTokenPair(body: String): TokenPair {
        // Response may be wrapped in {"payload": {...}}.
        val payloadMatch = Regex("\"payload\"\\s*:\\s*\\{").find(body)
        val obj = if (payloadMatch != null) {
            var d = 0; var k = payloadMatch.range.last
            do {
                when (body[k]) { '{' -> d++; '}' -> d-- }
                k++
            } while (d > 0 && k < body.length)
            body.substring(payloadMatch.range.last, k)
        } else body
        val access = strField(obj, "access_token")
            ?: throw ApiError(200, "refresh response missing access_token")
        val refresh = strField(obj, "refresh_token")
            ?: throw ApiError(200, "refresh response missing refresh_token")
        return TokenPair(access, refresh)
    }

    private fun strField(obj: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(obj)?.groupValues?.get(1)
}
