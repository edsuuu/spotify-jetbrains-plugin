package com.edsuuu.spotify.api

import com.edsuuu.spotify.settings.SpotifySettings
import com.google.gson.Gson
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class SpotifyAuthService {

    private val log = logger<SpotifyAuthService>()
    private val gson = Gson()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build()

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpiryMs: Long = 0
    private var callbackServer: HttpServer? = null
    private var activeFlow: AtomicBoolean? = null

    fun isConfigured(): Boolean = SpotifySettings.getInstance().clientId.isNotEmpty()

    fun isAuthorized(): Boolean = readRefreshToken() != null

    @Synchronized
    fun getValidAccessToken(): String? {
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < accessTokenExpiryMs - 30_000) return it }
        val refresh = readRefreshToken() ?: return null
        return refreshAccessToken(refresh)
    }

    @Synchronized
    fun logout() {
        accessToken = null
        accessTokenExpiryMs = 0
        storeRefreshToken(null)
    }

    @Synchronized
    fun authorize(onComplete: (Boolean, String) -> Unit) {
        val clientId = SpotifySettings.getInstance().clientId
        if (clientId.isEmpty()) {
            onComplete(false, "Informe o Client ID nas configurações antes de conectar.")
            return
        }
        val redirectUri = SpotifySettings.getInstance().redirectUri
        val redirect = runCatching { URI(redirectUri) }.getOrNull()
        if (redirect == null || redirect.isOpaque || redirect.host == null ||
            !redirect.scheme.equals("http", ignoreCase = true)
        ) {
            onComplete(false, "Redirect URI inválido: $redirectUri")
            return
        }
        val host = redirect.host
        val port = if (redirect.port > 0) redirect.port else 80
        val path = redirect.path.orEmpty().ifEmpty { "/" }

        activeFlow?.set(true)
        callbackServer?.let { runCatching { it.stop(0) } }
        callbackServer = null

        val verifier = PkceUtil.generateCodeVerifier()
        val challenge = PkceUtil.challengeFor(verifier)
        val stateNonce = PkceUtil.generateCodeVerifier()

        val server = try {
            HttpServer.create(InetSocketAddress(host, port), 0)
        } catch (e: Exception) {
            onComplete(false, "Não consegui abrir $host:$port para o login (porta ocupada?): ${e.message}")
            return
        }
        val completed = AtomicBoolean(false)
        activeFlow = completed
        callbackServer = server

        val completeOnce: (Boolean, String) -> Unit = { ok, message ->
            if (completed.compareAndSet(false, true)) {
                AppExecutorUtil.getAppScheduledExecutorService()
                    .schedule({ stopServer(server) }, 1, TimeUnit.SECONDS)
                onComplete(ok, message)
            }
        }

        server.createContext(path) { exchange ->
            handleCallback(exchange, clientId, redirectUri, verifier, stateNonce, completed, completeOnce)
        }
        server.executor = AppExecutorUtil.getAppExecutorService()
        server.start()

        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            completeOnce(false, "Tempo de login esgotado. Tente conectar novamente.")
        }, 3, TimeUnit.MINUTES)

        val authUrl = buildString {
            append(SpotifyUrls.authorize)
            append("?client_id=").append(enc(clientId))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(redirectUri))
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(enc(challenge))
            append("&scope=").append(enc(SCOPES))
            append("&state=").append(enc(stateNonce))
        }
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(authUrl), null)
        }
        BrowserUtil.browse(authUrl)
    }

    private fun handleCallback(
        exchange: HttpExchange,
        clientId: String,
        redirectUri: String,
        verifier: String,
        expectedState: String,
        completed: AtomicBoolean,
        completeOnce: (Boolean, String) -> Unit,
    ) {
        val params = parseQuery(exchange.requestURI)
        if (params["state"] != expectedState || completed.get()) {
            respond(exchange, false, "Requisição ignorada.")
            return
        }
        val error = params["error"]
        val code = params["code"]
        val (ok, message) = when {
            error != null -> false to "Autorização negada: $error"
            code == null -> false to "Nenhum código recebido do Spotify."
            else -> exchangeCodeForToken(clientId, redirectUri, code, verifier)
        }
        respond(exchange, ok, message)
        completeOnce(ok, message)
    }

    private fun exchangeCodeForToken(
        clientId: String, redirectUri: String, code: String, verifier: String,
    ): Pair<Boolean, String> {
        val form = formEncode(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier,
        )
        val token = postToken(form) ?: return false to "Não consegui trocar o código pelo token."
        if (token.accessToken == null || token.refreshToken == null) {
            return false to (token.errorDescription ?: token.error ?: "Resposta de token inválida.")
        }
        applyToken(token)
        storeRefreshToken(token.refreshToken)
        return true to "Conectado ao Spotify com sucesso."
    }

    private fun refreshAccessToken(refreshToken: String): String? {
        val clientId = SpotifySettings.getInstance().clientId
        if (clientId.isEmpty()) return null
        val form = formEncode(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId,
        )
        val token = postToken(form) ?: return null
        if (token.accessToken == null) {
            log.warn("Refresh failed: ${token.error} ${token.errorDescription}")
            if (token.error == "invalid_grant") storeRefreshToken(null)
            return null
        }
        applyToken(token)
        token.refreshToken?.let { storeRefreshToken(it) }
        return token.accessToken
    }

    private fun applyToken(token: TokenResponse) {
        accessToken = token.accessToken
        accessTokenExpiryMs = System.currentTimeMillis() + (token.expiresIn ?: 3600) * 1000
    }

    private fun postToken(form: String): TokenResponse? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(SpotifyUrls.token))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(java.time.Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        return try {
            val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
            gson.fromJson(resp.body(), TokenResponse::class.java)
        } catch (e: Exception) {
            log.warn("Token request failed", e)
            null
        }
    }

    @Synchronized
    private fun stopServer(server: HttpServer) {
        runCatching { server.stop(0) }
        if (callbackServer === server) callbackServer = null
    }

    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributes(generateServiceName("Spotify Player", "refresh-token"))

    private fun readRefreshToken(): String? =
        PasswordSafe.instance.getPassword(credentialAttributes())?.takeIf { it.isNotEmpty() }

    private fun storeRefreshToken(value: String?) {
        val creds = if (value == null) null else Credentials("spotify", value)
        PasswordSafe.instance.set(credentialAttributes(), creds)
    }

    private fun respond(exchange: HttpExchange, ok: Boolean, message: String) {
        val title = if (ok) "Tudo certo! 🎧" else "Ops…"
        val html = """
            <!doctype html><html lang="pt-br"><head><meta charset="utf-8">
            <title>Spotify Player</title></head>
            <body style="font-family:-apple-system,Segoe UI,sans-serif;background:#121212;color:#fff;
            display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
            <div style="text-align:center">
            <h1 style="color:#1DB954">$title</h1>
            <p>$message</p>
            <p style="opacity:.6">Você já pode fechar esta aba e voltar à sua IDE.</p>
            </div></body></html>
        """.trimIndent()
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val SCOPES =
            "user-read-playback-state user-modify-playback-state " +
                "user-read-currently-playing user-library-read user-library-modify"

        fun getInstance(): SpotifyAuthService =
            ApplicationManager.getApplication().getService(SpotifyAuthService::class.java)

        private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

        private fun formEncode(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        private fun parseQuery(uri: URI): Map<String, String> =
            uri.rawQuery?.split("&")?.mapNotNull {
                val i = it.indexOf('='); if (i < 0) null else
                    java.net.URLDecoder.decode(it.substring(0, i), StandardCharsets.UTF_8) to
                        java.net.URLDecoder.decode(it.substring(i + 1), StandardCharsets.UTF_8)
            }?.toMap().orEmpty()
    }
}
