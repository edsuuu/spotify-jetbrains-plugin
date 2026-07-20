package com.edsuuu.spotify.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object PkceUtil {

    private const val VERIFIER_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val sb = StringBuilder(64)
        repeat(64) { sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.length)]) }
        return sb.toString()
    }

    fun challengeFor(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
