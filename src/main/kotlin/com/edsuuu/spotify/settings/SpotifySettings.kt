package com.edsuuu.spotify.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "SpotifyPlayerSettings", storages = [Storage("spotify-player.xml")])
class SpotifySettings : PersistentStateComponent<SpotifySettings.State> {

    class State {
        @JvmField var clientId: String = ""
        @JvmField var redirectUri: String = DEFAULT_REDIRECT_URI
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(loaded: State) = XmlSerializerUtil.copyBean(loaded, state)

    var clientId: String
        get() = state.clientId.trim()
        set(value) { state.clientId = value.trim() }

    var redirectUri: String
        get() = state.redirectUri.trim().ifEmpty { DEFAULT_REDIRECT_URI }
        set(value) { state.redirectUri = value.trim().ifEmpty { DEFAULT_REDIRECT_URI } }

    companion object {
        const val DEFAULT_REDIRECT_URI = "http://127.0.0.1:8888/callback"

        fun getInstance(): SpotifySettings =
            ApplicationManager.getApplication().getService(SpotifySettings::class.java)
    }
}
