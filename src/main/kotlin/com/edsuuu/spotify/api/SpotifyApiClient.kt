package com.edsuuu.spotify.api

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

data class ActionResult(val success: Boolean, val error: String? = null) {
    companion object {
        val OK = ActionResult(true)
        fun fail(msg: String) = ActionResult(false, msg)
    }
}

class SpotifyApiClient(private val auth: SpotifyAuthService = SpotifyAuthService.getInstance()) {

    private val log = logger<SpotifyApiClient>()
    private val gson = Gson()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build()

    @Volatile private var backoffUntilMs = 0L

    fun getQueue(): QueueResponse? {
        val resp = send("GET", "/me/player/queue") ?: return null
        if (resp.statusCode() == 204 || resp.body().isNullOrBlank()) return null
        if (resp.statusCode() !in 200..299) return null
        return runCatching { gson.fromJson(resp.body(), QueueResponse::class.java) }.getOrNull()
    }

    fun getPlaybackState(): PlaybackState? {
        val resp = send("GET", "/me/player?additional_types=track,episode") ?: return null
        if (resp.statusCode() == 204 || resp.body().isNullOrBlank()) return null
        if (resp.statusCode() !in 200..299) return null
        return runCatching { gson.fromJson(resp.body(), PlaybackState::class.java) }.getOrNull()
    }

    fun areSaved(ids: List<String>): Map<String, Boolean> {
        if (ids.isEmpty()) return emptyMap()
        val query = ids.joinToString(",") { enc(it) }
        val resp = send("GET", "/me/tracks/contains?ids=$query") ?: return emptyMap()
        if (resp.statusCode() !in 200..299) return emptyMap()
        val flags = runCatching { gson.fromJson(resp.body(), BooleanArray::class.java) }.getOrNull()
            ?: return emptyMap()
        return ids.zip(flags.toList()).toMap()
    }

    fun play(): ActionResult = interpret(send("PUT", "/me/player/play", body = ""))
    fun pause(): ActionResult = interpret(send("PUT", "/me/player/pause", body = ""))
    fun next(): ActionResult = interpret(send("POST", "/me/player/next", body = ""))
    fun previous(): ActionResult = interpret(send("POST", "/me/player/previous", body = ""))

    fun setShuffle(on: Boolean): ActionResult =
        interpret(send("PUT", "/me/player/shuffle?state=$on", body = ""))

    fun playTrack(uri: String): ActionResult =
        interpret(send("PUT", "/me/player/play", body = gson.toJson(mapOf("uris" to listOf(uri)))))

    fun playTrackInContext(trackUri: String, contextUri: String?): ActionResult {
        if (contextUri != null) {
            val body = gson.toJson(
                mapOf("context_uri" to contextUri, "offset" to mapOf("uri" to trackUri))
            )
            val result = interpret(send("PUT", "/me/player/play", body = body))
            if (result.success) return result
        }
        return playTrack(trackUri)
    }

    fun setSaved(trackId: String, saved: Boolean): ActionResult {
        val method = if (saved) "PUT" else "DELETE"
        return interpret(send(method, "/me/tracks?ids=${enc(trackId)}", body = ""))
    }

    private fun send(method: String, path: String, body: String? = null): HttpResponse<String>? {
        if (System.currentTimeMillis() < backoffUntilMs) return null
        val token = auth.getValidAccessToken() ?: return null
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(SpotifyUrls.apiBase + path))
            .header("Authorization", "Bearer $token")
            .timeout(java.time.Duration.ofSeconds(20))
        val publisher = HttpRequest.BodyPublishers.ofString(body ?: "")
        when (method) {
            "GET" -> builder.GET()
            "PUT" -> builder.header("Content-Type", "application/json").PUT(publisher)
            "POST" -> builder.header("Content-Type", "application/json").POST(publisher)
            "DELETE" -> builder.method("DELETE", publisher)
        }
        return try {
            val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 429) {
                val retryAfter =
                    resp.headers().firstValue("Retry-After").orElse("").toLongOrNull() ?: 5L
                backoffUntilMs = System.currentTimeMillis() + retryAfter * 1000
            }
            resp
        } catch (e: Exception) {
            log.warn("Spotify request failed: $method $path", e)
            null
        }
    }

    private fun interpret(resp: HttpResponse<String>?): ActionResult = when (resp?.statusCode()) {
        null -> ActionResult.fail("Sem conexão com o Spotify.")
        in 200..299 -> ActionResult.OK
        401 -> ActionResult.fail("Sessão expirada. Reconecte nas configurações.")
        403 -> ActionResult.fail("Ação não permitida (requer Premium ou está restrita).")
        404 -> ActionResult.fail("Nenhum dispositivo ativo. Abra o Spotify em algum aparelho.")
        429 -> ActionResult.fail("Muitas requisições — aguarde um instante.")
        else -> ActionResult.fail("Erro do Spotify (${resp.statusCode()}).")
    }

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
    }
}
