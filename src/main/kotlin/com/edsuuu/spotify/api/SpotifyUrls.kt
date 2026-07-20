package com.edsuuu.spotify.api

import java.util.Properties

object SpotifyUrls {
    private val props = Properties().apply {
        SpotifyUrls::class.java.getResourceAsStream("/spotify.properties")?.use { load(it) }
    }

    val apiBase: String = props.getProperty("spotify.api.base.url", "https://api.spotify.com/v1")
    val authorize: String =
        props.getProperty("spotify.accounts.authorize.url", "https://accounts.spotify.com/authorize")
    val token: String =
        props.getProperty("spotify.accounts.token.url", "https://accounts.spotify.com/api/token")
}
