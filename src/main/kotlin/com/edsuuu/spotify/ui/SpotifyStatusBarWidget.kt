package com.edsuuu.spotify.ui

import com.edsuuu.spotify.api.SpotifyApiClient
import com.edsuuu.spotify.api.SpotifyAuthService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Component
import java.awt.event.MouseEvent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SpotifyStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "Spotify — Tocando agora"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = SpotifyStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID = "SpotifyNowPlaying"
    }
}

class SpotifyStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    private val auth = SpotifyAuthService.getInstance()
    private val api = SpotifyApiClient(auth)
    private var statusBar: StatusBar? = null
    private var poller: ScheduledFuture<*>? = null

    @Volatile private var fullText = ""

    override fun ID(): String = SpotifyStatusBarWidgetFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        poller = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            val updated = runCatching { computeText() }.getOrDefault("")
            if (updated != fullText) {
                fullText = updated
                statusBar.updateWidget(ID())
            }
        }, 2, 5, TimeUnit.SECONDS)
    }

    private fun computeText(): String {
        if (!auth.isConfigured() || !auth.isAuthorized()) return ""
        val state = api.getPlaybackState() ?: return ""
        val track = state.item ?: return ""
        val glyph = if (state.isPlaying == true) "▶" else "⏸"
        val artists = track.artistNames()
        return buildString {
            append(glyph).append(' ').append(track.name ?: "—")
            if (artists.isNotEmpty()) append(" — ").append(artists)
        }
    }

    override fun getText(): String {
        val full = fullText
        return if (full.length <= MAX_LEN) full else full.take(MAX_LEN - 1) + "…"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        return "Cique para abrir o player."
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow("Spotify")?.show(null)
        }, ModalityState.any())
    }

    override fun dispose() {
        poller?.cancel(true)
        poller = null
        statusBar = null
    }

    companion object {
        private const val MAX_LEN = 30
    }
}
