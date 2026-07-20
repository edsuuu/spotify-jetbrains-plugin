package com.edsuuu.spotify.ui

import com.edsuuu.spotify.api.ActionResult
import com.edsuuu.spotify.api.PlaybackState
import com.edsuuu.spotify.api.SpotifyApiClient
import com.edsuuu.spotify.api.SpotifyAuthService
import com.edsuuu.spotify.api.Track
import com.edsuuu.spotify.settings.SpotifyConfigurable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

class SpotifyPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val auth = SpotifyAuthService.getInstance()
    private val api = SpotifyApiClient(auth)

    private val albumArt = JLabel().apply {
        preferredSize = Dimension(56, 56)
        horizontalAlignment = SwingConstants.CENTER
    }
    private val titleLabel = JBLabel("—").apply { font = font.deriveFont(Font.BOLD, 13f) }
    private val artistLabel = JBLabel(" ").apply { foreground = UIUtil.getContextHelpForeground() }
    private val favoriteButton = glyphButton("♡", "Favoritar / remover dos favoritos", 13f).apply {
        isContentAreaFilled = false
        isBorderPainted = false
        margin = JBUI.emptyInsets()
    }
    private val progressBar = JProgressBar(0, 1000).apply { isStringPainted = false }
    private val elapsedLabel = timeLabel()
    private val totalLabel = timeLabel()
    private val prevButton = flatButton("⏮", "Anterior", 16f)
    private val playPauseButton = flatButton("▶", "Play / Pause", 20f)
    private val nextButton = flatButton("⏭", "Próxima", 16f)
    private val shuffleButton = flatButton("⇄", "Modo aleatório (ativar/desativar)", 17f)
    private val queueModel = DefaultListModel<Track>()

    private val queueList = object : JBList<Track>(queueModel) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)
    private var poller: ScheduledFuture<*>? = null
    private val smoothTimer = javax.swing.Timer(250) { tickSmooth() }
    private var pollCount = 0

    @Volatile private var currentTrack: Track? = null
    @Volatile private var currentSaved: Boolean = false
    private val savedMap = HashMap<String, Boolean>()
    private var lastArtUrl: String? = null
    private val queueArtCache = HashMap<String, ImageIcon>()
    private val queueArtLoading = HashSet<String>()
    private var hoveredIndex = -1

    @Volatile private var isPlaying = false
    @Volatile private var shuffleOn = false
    @Volatile private var smartShuffleOn = false
    @Volatile private var currentContextUri: String? = null
    @Volatile private var authProgressMs = 0L
    @Volatile private var authWallMs = 0L
    @Volatile private var durationMs = 0L

    init {
        cards.add(buildSetupCard(), CARD_SETUP)
        cards.add(buildPlayerCard(), CARD_PLAYER)
        add(cards, BorderLayout.CENTER)
        cardLayout.show(cards, CARD_SETUP)

        wireActions()
        smoothTimer.start()
        poller = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::pollOnce, 300, 1000, TimeUnit.MILLISECONDS)
    }

    private fun buildSetupCard(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(24, 16)
        val msg = JBLabel(
            "<html><div style='text-align:center'><b>Spotify Player</b><br><br>" +
                "Conecte sua conta do Spotify para começar.</div></html>"
        ).apply { alignmentX = Component.CENTER_ALIGNMENT }
        val openSettings = JButton("Abrir configurações").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SpotifyConfigurable::class.java)
            }
        }
        panel.add(Box.createVerticalGlue())
        panel.add(msg)
        panel.add(Box.createVerticalStrut(16))
        panel.add(openSettings)
        panel.add(Box.createVerticalGlue())
        return panel
    }

    private fun buildPlayerCard(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply { border = JBUI.Borders.empty(10) }

        val texts = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(artistLabel, BorderLayout.CENTER)
        }
        val header = JPanel(BorderLayout(8, 0)).apply {
            add(albumArt, BorderLayout.WEST)
            add(texts, BorderLayout.CENTER)
            add(favoriteButton, BorderLayout.EAST)
        }

        val progress = JPanel(BorderLayout(6, 0)).apply {
            add(elapsedLabel, BorderLayout.WEST)
            add(progressBar, BorderLayout.CENTER)
            add(totalLabel, BorderLayout.EAST)
        }

        val controls = JPanel(FlowLayout(FlowLayout.CENTER, 14, 2)).apply {
            add(prevButton); add(playPauseButton); add(nextButton); add(shuffleButton)
        }

        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(header)
            add(Box.createVerticalStrut(8))
            add(progress)
            add(Box.createVerticalStrut(4))
            add(controls)
        }

        queueList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        queueList.cellRenderer = TrackRenderer()
        queueList.emptyText.text = "Fila vazia"
        queueList.setExpandableItemsEnabled(false)
        val queueScroll = JBScrollPane(queueList).apply {
            border = BorderFactory.createTitledBorder("Próximas na fila")
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        root.add(north, BorderLayout.NORTH)
        root.add(queueScroll, BorderLayout.CENTER)
        return root
    }

    private fun wireActions() {
        prevButton.addActionListener { control { api.previous() } }
        nextButton.addActionListener { control { api.next() } }
        playPauseButton.addActionListener {
            control { if (isPlaying) api.pause() else api.play() }
        }
        shuffleButton.addActionListener { control { api.setShuffle(!shuffleOn) } }
        favoriteButton.addActionListener {
            val id = currentTrack?.id ?: return@addActionListener
            val want = !currentSaved
            runInBg({ api.setSaved(id, want) }) { r ->
                if (r.success) {
                    currentSaved = want
                    savedMap[id] = want
                    renderFavorite()
                } else notifyError(r.error)
            }
        }
        queueList.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        queueList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
            override fun mouseExited(e: MouseEvent) = setHovered(-1)
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                val index = queueList.locationToIndex(e.point)
                if (index < 0) return
                if (queueList.getCellBounds(index, index)?.contains(e.point) != true) return
                val uri = queueModel.getElementAt(index).uri ?: return
                val context = currentContextUri
                control { api.playTrackInContext(uri, context) }
            }
        })
        queueList.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = queueList.locationToIndex(e.point)
                val onRow = index >= 0 && queueList.getCellBounds(index, index)?.contains(e.point) == true
                setHovered(if (onRow) index else -1)
            }
        })
    }

    private fun setHovered(index: Int) {
        if (index == hoveredIndex) return
        hoveredIndex = index
        queueList.repaint()
    }

    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = queueList.locationToIndex(e.point)
        if (index < 0) return
        queueList.selectedIndex = index
        val track = queueModel.getElementAt(index)
        val id = track.id ?: return
        val saved = savedMap[id] == true
        val menu = JPopupMenu()
        val item = menu.add(if (saved) "Remover dos favoritos" else "Favoritar")
        item.addActionListener {
            runInBg({ api.setSaved(id, !saved) }) { r ->
                if (r.success) { savedMap[id] = !saved } else notifyError(r.error)
            }
        }
        menu.show(queueList, e.x, e.y)
    }

    @Synchronized
    private fun pollOnce() {
        val ready = auth.isConfigured() && auth.isAuthorized()
        onEdt { cardLayout.show(cards, if (ready) CARD_PLAYER else CARD_SETUP) }
        if (!ready) return

        try {
            val state = api.getPlaybackState()
            applyPlayback(state)

            if (pollCount % 3 == 0) {
                val queue = api.getQueue()?.queue.orEmpty()
                val ids = (listOfNotNull(currentTrack?.id) + queue.mapNotNull { it.id }).distinct()
                val saved = api.areSaved(ids)
                onEdt {
                    queueModel.clear()
                    queue.forEach { queueModel.addElement(it) }
                    savedMap.putAll(saved)
                    currentTrack?.id?.let { currentSaved = savedMap[it] == true }
                    renderFavorite()
                }
            }
        } catch (e: Exception) {
        } finally {
            pollCount++
        }
    }

    private fun applyPlayback(state: PlaybackState?) {
        val track = state?.item
        isPlaying = state?.isPlaying == true
        shuffleOn = state?.shuffleState == true
        smartShuffleOn = state?.smartShuffle == true
        currentContextUri = state?.context?.uri
        authProgressMs = state?.progressMs ?: 0L
        authWallMs = System.currentTimeMillis()
        durationMs = track?.durationMs ?: 0L
        val changed = track?.id != currentTrack?.id
        currentTrack = track

        onEdt {
            playPauseButton.text = if (isPlaying) "⏸" else "▶"
            shuffleButton.foreground =
                if (shuffleOn || smartShuffleOn) SPOTIFY_GREEN else UIUtil.getLabelForeground()
            shuffleButton.toolTipText = when {
                smartShuffleOn ->
                    "Ordem aleatória inteligente ativa (a API do Spotify só permite ativá-la no app; clique para desligar)"
                shuffleOn -> "Modo aleatório: ativado (clique para desativar)"
                else -> "Modo aleatório: desativado (clique para ativar)"
            }
            if (track == null) {
                titleLabel.text = "Nada tocando"
                artistLabel.text = " "
            } else {
                titleLabel.text = track.name ?: "—"
                artistLabel.text = track.artistNames().ifBlank { " " }
            }
            renderFavorite()
        }
        if (changed) loadArt(track?.thumbnailUrl())
    }

    private fun tickSmooth() {
        val dur = durationMs
        if (dur <= 0) {
            progressBar.value = 0
            elapsedLabel.text = "0:00"
            totalLabel.text = "0:00"
            return
        }
        val shown = if (isPlaying)
            (authProgressMs + (System.currentTimeMillis() - authWallMs)).coerceAtMost(dur)
        else authProgressMs
        progressBar.value = ((shown.toDouble() / dur) * 1000).toInt().coerceIn(0, 1000)
        elapsedLabel.text = formatMs(shown)
        totalLabel.text = formatMs(dur)
    }

    private fun renderFavorite() {
        val saved = currentTrack?.id?.let { savedMap[it] } ?: currentSaved
        currentSaved = saved
        favoriteButton.text = if (saved) "♥" else "♡"
        favoriteButton.foreground = if (saved) SPOTIFY_GREEN else UIUtil.getLabelForeground()
    }

    private fun loadArt(url: String?) {
        if (url == lastArtUrl) return
        lastArtUrl = url
        if (url == null) { onEdt { albumArt.icon = null }; return }
        AppExecutorUtil.getAppExecutorService().execute {
            val icon = runCatching {
                val img = ImageIO.read(URI.create(url).toURL())
                ImageIcon(img.getScaledInstance(56, 56, java.awt.Image.SCALE_SMOOTH))
            }.getOrNull()
            onEdt { if (url == lastArtUrl) albumArt.icon = icon }
        }
    }

    private fun queueIcon(track: Track): ImageIcon? {
        val url = track.thumbnailUrl() ?: return null
        queueArtCache[url]?.let { return it }
        if (queueArtLoading.add(url)) {
            AppExecutorUtil.getAppExecutorService().execute {
                val icon = runCatching {
                    val img = ImageIO.read(URI.create(url).toURL())
                    ImageIcon(img.getScaledInstance(QUEUE_ART, QUEUE_ART, java.awt.Image.SCALE_SMOOTH))
                }.getOrNull()
                onEdt {
                    queueArtLoading.remove(url)
                    if (icon != null) {
                        if (queueArtCache.size > 200) queueArtCache.clear()
                        queueArtCache[url] = icon
                        queueList.repaint()
                    }
                }
            }
        }
        return null
    }

    private fun control(action: () -> ActionResult) {
        runInBg(action) { r ->
            if (!r.success) notifyError(r.error)
            AppExecutorUtil.getAppExecutorService().execute { runCatching { pollOnce() } }
        }
    }

    private fun <T> runInBg(task: () -> T, onDone: (T) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            val result = task()
            onEdt { onDone(result) }
        }
    }

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    private fun notifyError(message: String?) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spotify Player")
            .createNotification(message ?: "Erro no Spotify.", NotificationType.WARNING)
            .notify(project)
    }

    override fun dispose() {
        poller?.cancel(true)
        smoothTimer.stop()
    }

    private inner class TrackRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            val track = value as Track
            val artists = track.artistNames()
            val text = buildString {
                append(track.name ?: "—")
                if (artists.isNotEmpty()) { append("  —  "); append(artists) }
            }
            super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            icon = queueIcon(track)
            iconTextGap = 8
            border = JBUI.Borders.empty(3, 6)
            if (!isSelected && index == hoveredIndex) {
                isOpaque = true
                background = HOVER_BG
            }
            return this
        }
    }

    companion object {
        private const val CARD_SETUP = "setup"
        private const val CARD_PLAYER = "player"
        private const val QUEUE_ART = 28
        private val SPOTIFY_GREEN = java.awt.Color(0x1D, 0xB9, 0x54)
        private val HOVER_BG = ColorUtil.mix(UIUtil.getListBackground(), UIUtil.getListForeground(), 0.18)

        private fun glyphButton(glyph: String, tooltip: String, size: Float = 16f): JButton =
            JButton(glyph).apply {
                toolTipText = tooltip
                font = font.deriveFont(Font.PLAIN, size)
                isFocusable = false
                margin = JBUI.insets(2, 8)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

        private fun flatButton(glyph: String, tooltip: String, size: Float): JButton =
            glyphButton(glyph, tooltip, size).apply {
                isContentAreaFilled = false
                isBorderPainted = false
                margin = JBUI.emptyInsets()
                border = JBUI.Borders.empty(2, 4)
                preferredSize = Dimension(JBUI.scale(38), JBUI.scale(30))
            }

        private fun timeLabel(): JBLabel =
            JBLabel("0:00").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = font.deriveFont(11f)
            }

        private fun formatMs(ms: Long): String {
            val totalSec = ms / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}
