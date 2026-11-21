package com.geofilho.carteirarapidag

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Quando a Samsung Wallet abre por causa do botão Voltar pressionado,
 * abre a Carteira do Google por cima dela.
 *
 * Como funciona:
 * - Se o relógio entrega ao serviço os toques no botão Voltar, só redireciona quando a
 *   Samsung Wallet abre logo depois de o botão ser pressionado (abrir a Samsung Wallet
 *   pela lista de apps continua funcionando normalmente).
 * - Se o relógio não entrega esses toques (a Samsung pode reservar o botão), redireciona
 *   sempre que a Samsung Wallet abrir.
 */
class BotaoVoltarService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** Momento em que o botão Voltar foi pressionado pela última vez. */
    private var ultimoVoltar = 0L

    /** Verdadeiro depois que o relógio entregar ao menos um toque no botão Voltar. */
    private var recebeBotao = false

    /** Evita abrir a Carteira do Google duas vezes seguidas. */
    private var ultimaAbertura = 0L

    /** Quando o NFC é ligado depois do aviso, abre a Carteira do Google sozinho. */
    private val receptorNfc = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val estado = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
            if (estado == NfcAdapter.STATE_ON && Carteiras.consumirEsperaNfc(context)) {
                handler.postDelayed({ Carteiras.abrirGoogleWallet(context) }, ATRASO_NFC_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filtro = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receptorNfc, filtro, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receptorNfc, filtro)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            recebeBotao = true
            ultimoVoltar = SystemClock.uptimeMillis()
        }
        // Nunca bloqueia o botão: Voltar continua funcionando normalmente.
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Carteiras.ehSamsungWallet(event.packageName?.toString())) return

        val agora = SystemClock.uptimeMillis()
        val veioDoBotao = !recebeBotao || agora - ultimoVoltar < JANELA_BOTAO_MS
        if (!veioDoBotao) return
        if (agora - ultimaAbertura < INTERVALO_MINIMO_MS) return
        if (!Carteiras.googleWalletInstalada(this)) return // sem ela, deixa a Samsung Wallet abrir

        ultimaAbertura = agora
        // Pequena espera para a Samsung Wallet terminar de abrir e a próxima tela ficar por cima
        handler.postDelayed({
            if (Carteiras.nfcDesligado(this)) {
                // NFC desligado: abre a chave do NFC; ao ligar, a Carteira do Google abre sozinha
                Carteiras.ligarNfcEVoltar(this)
            } else {
                Carteiras.abrirGoogleWallet(this)
            }
        }, ATRASO_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(receptorNfc)
        } catch (e: Exception) {
            // já não estava registrado
        }
        super.onDestroy()
    }

    private companion object {
        const val JANELA_BOTAO_MS = 4_000L
        const val INTERVALO_MINIMO_MS = 2_000L
        const val ATRASO_MS = 120L
        const val ATRASO_NFC_MS = 400L
    }
}
