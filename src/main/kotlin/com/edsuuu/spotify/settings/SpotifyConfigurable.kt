package com.edsuuu.spotify.settings

import com.edsuuu.spotify.api.SpotifyAuthService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class SpotifyConfigurable : Configurable {

    private val clientIdField = JBPasswordField().apply { columns = 40 }
    private val maskChar = clientIdField.echoChar
    private val revealButton = JButton(AllIcons.Actions.Show).apply {
        toolTipText = "Mostrar / ocultar o Client ID"
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val redirectField = JBTextField(40)
    private val statusLabel = JBLabel()
    private val connectButton = JButton("Conectar ao Spotify")
    private val disconnectButton = JButton("Desconectar")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Spotify Player"

    override fun createComponent(): JComponent {
        val auth = SpotifyAuthService.getInstance()

        val steps = JBLabel(
            "<html><b>Configuração (1x, ~2 min):</b><br>" +
                "1. Em <a href='https://developer.spotify.com/dashboard'>developer.spotify.com/dashboard</a> crie/abra um app.<br>" +
                "2. Em <b>Redirect URIs</b> cadastre exatamente o mesmo valor do campo \"Redirect URI\" abaixo.<br>" +
                "3. Marque <b>Web API</b>, salve e copie o <b>Client ID</b> aqui.</html>"
        ).apply { foreground = UIUtil.getContextHelpForeground() }

        revealButton.addActionListener {
            val hidden = clientIdField.echoChar != 0.toChar()
            clientIdField.echoChar = if (hidden) 0.toChar() else maskChar
            revealButton.toolTipText = if (hidden) "Ocultar o Client ID" else "Mostrar o Client ID"
        }
        connectButton.addActionListener { onConnect() }
        disconnectButton.addActionListener {
            auth.logout()
            statusLabel.text = ""
            refreshStatus()
        }

        val clientIdRow = JPanel(BorderLayout(4, 0)).apply {
            add(clientIdField, BorderLayout.CENTER)
            add(revealButton, BorderLayout.EAST)
        }

        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(connectButton)
            add(Box.createHorizontalStrut(8))
            add(disconnectButton)
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(steps)
            .addLabeledComponent("Client ID:", clientIdRow)
            .addLabeledComponent("Redirect URI:", redirectField)
            .addComponent(buttons)
            .addComponent(statusLabel)
            .panel

        panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            add(form, BorderLayout.NORTH)
        }

        reset()
        return panel!!
    }

    private fun onConnect() {
        apply()
        if (SpotifySettings.getInstance().clientId.isEmpty()) {
            statusLabel.text = "Informe o Client ID primeiro."
            return
        }
        statusLabel.text =
            "Abrindo o navegador… (a URL de login também foi copiada — cole no navegador se a aba não aparecer)"
        connectButton.isEnabled = false
        SpotifyAuthService.getInstance().authorize { success, message ->
            ApplicationManager.getApplication().invokeLater({
                connectButton.isEnabled = true
                statusLabel.text = message
                refreshStatus()
                if (!success) {
                    Messages.showWarningDialog(message, "Spotify Player")
                }
            }, ModalityState.any())
        }
    }

    private fun refreshStatus() {
        val auth = SpotifyAuthService.getInstance()
        val connected = auth.isAuthorized()
        disconnectButton.isEnabled = connected
        if (statusLabel.text.isNullOrBlank() || connected) {
            statusLabel.text = if (connected) "✔ Conectado ao Spotify." else "Não conectado."
        }
    }

    private fun clientIdText(): String = String(clientIdField.password).trim()

    override fun isModified(): Boolean {
        val s = SpotifySettings.getInstance()
        return clientIdText() != s.clientId || redirectField.text.trim() != s.redirectUri
    }

    override fun apply() {
        val s = SpotifySettings.getInstance()
        s.clientId = clientIdText()
        s.redirectUri = redirectField.text
    }

    override fun reset() {
        val s = SpotifySettings.getInstance()
        clientIdField.text = s.clientId
        clientIdField.echoChar = maskChar
        revealButton.toolTipText = "Mostrar o Client ID"
        redirectField.text = s.redirectUri
        refreshStatus()
    }
}
