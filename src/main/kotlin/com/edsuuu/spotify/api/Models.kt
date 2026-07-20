package com.edsuuu.spotify.api

import com.google.gson.annotations.SerializedName

data class Artist(val name: String?)

data class Image(val url: String?, val width: Int?, val height: Int?)

data class Album(val name: String?, val images: List<Image>?)

data class Track(
    val id: String?,
    val uri: String?,
    val name: String?,
    val artists: List<Artist>?,
    val album: Album?,
    @SerializedName("duration_ms") val durationMs: Long?,
) {
    fun artistNames(): String = artists.orEmpty().mapNotNull { it.name }.joinToString(", ")

    fun thumbnailUrl(): String? =
        album?.images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url
}

data class QueueResponse(
    @SerializedName("currently_playing") val currentlyPlaying: Track?,
    val queue: List<Track>?,
)

data class PlayContext(val uri: String?, val type: String?)

data class PlaybackState(
    val item: Track?,
    val context: PlayContext?,
    @SerializedName("progress_ms") val progressMs: Long?,
    @SerializedName("is_playing") val isPlaying: Boolean?,
    @SerializedName("shuffle_state") val shuffleState: Boolean?,
    @SerializedName("smart_shuffle") val smartShuffle: Boolean?,
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_in") val expiresIn: Long?,
    @SerializedName("token_type") val tokenType: String?,
    val error: String?,
    @SerializedName("error_description") val errorDescription: String?,
)
