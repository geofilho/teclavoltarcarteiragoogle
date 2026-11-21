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
 * Botão Voltar:
 * - Se o relógio entrega ao serviço os toques no botão Voltar, só redireciona quando a
 *   Samsung Wallet abre logo depois de o botão ser pressionado.
 * - Se o relógio não entrega esses toques, redireciona sempre que a Samsung Wallet abrir.
 *
 * NFC (só quando ele estava desligado):
 * 1. Abre a chave do NFC. Ao ligar, a Carteira do Google abre sozinha.
 * 2. Ao sair da Carteira do Google, abre a chave do NFC de novo.
 * 3. Ao desligar, volta para o mostrador do relógio.
 * Se o NFC já estava ligado, nada disso acontece.
 */
class BotaoVoltarService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var ultimoVoltar = 0L
    private var recebeBotao = false
    private var ultimaAbertura = 0L

    /** A Carteira do Google já apareceu na tela depois que o app ligou o NFC. */
    private var viuGoogleWallet = false

    /** Estamos mostrando a chave do NFC para a pessoa desligar. */
    private var aguardandoDesligar = false
    private var aguardandoDesde = 0L
    private var pacoteAjustes: String? = null

    private val receptorNfc = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)) {
                NfcAdapter.STATE_ON -> if (Carteiras.consumirEsperaNfc(context)) {
                    Carteiras.marcarNfcLigadoPeloApp(context, true)
                    viuGoogleWallet = false
                    handler.postDelayed({ Carteiras.abrirGoogleWallet(context) }, ATRASO_NFC_MS)
                }
                NfcAdapter.STATE_OFF -> {
                    val fechar = aguardandoDesligar
                    encerrarSessaoNfc()
                    if (fechar) {
                        // Fecha tudo: volta para o mostrador do relógio
                        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, ATRASO_NFC_MS)
                    }
                }
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
        val pacote = event.packageName?.toString() ?: return

        if (Carteiras.ehSamsungWallet(pacote)) {
            redirecionarSamsungWallet()
            return
        }
        acompanharSessaoNfc(pacote)
    }

    private fun redirecionarSamsungWallet() {
        val agora = SystemClock.uptimeMillis()
        val veioDoBotao = !recebeBotao || agora - ultimoVoltar < JANELA_BOTAO_MS
        if (!veioDoBotao) return
        if (agora - ultimaAbertura < INTERVALO_MINIMO_MS) return
        if (!Carteiras.googleWalletInstalada(this)) return // sem ela, deixa a Samsung Wallet abrir

        ultimaAbertura = agora
        handler.postDelayed({
            if (Carteiras.nfcDesligado(this)) {
                // NFC desligado: abre a chave do NFC; ao ligar, a Carteira do Google abre sozinha
                Carteiras.ligarNfcEVoltar(this)
            } else {
                Carteiras.abrirGoogleWallet(this)
            }
        }, ATRASO_MS)
    }

    /** Só age quando foi o app que ligou o NFC. */
    private fun acompanharSessaoNfc(pacote: String) {
        if (pacote == Carteiras.GOOGLE_WALLET) {
            if (Carteiras.nfcLigadoPeloApp(this)) viuGoogleWallet = true
            return
        }
        if (pacote in PACOTES_IGNORADOS || pacote == packageName) return

        if (aguardandoDesligar) {
            val agora = SystemClock.uptimeMillis()
            if (agora - aguardandoDesde > ESPERA_DESLIGAR_MS) {
                encerrarSessaoNfc()
                return
            }
            if (pacoteAjustes == null) {
                pacoteAjustes = pacote // a primeira tela que aparece é a dos ajustes do NFC
            } else if (pacote != pacoteAjustes) {
                encerrarSessaoNfc() // saiu sem desligar: não insiste
            }
            return
        }

        if (Carteiras.nfcLigadoPeloApp(this) && viuGoogleWallet && !Carteiras.nfcDesligado(this)) {
            // Saiu da Carteira do Google: oferece desligar o NFC de novo
            aguardandoDesligar = true
            aguardandoDesde = SystemClock.uptimeMillis()
            pacoteAjustes = null
            viuGoogleWallet = false
            handler.postDelayed({ Carteiras.abrirAjustesNfc(this) }, ATRASO_MS)
        }
    }

    private fun encerrarSessaoNfc() {
        Carteiras.marcarNfcLigadoPeloApp(this, false)
        aguardandoDesligar = false
        pacoteAjustes = null
        viuGoogleWallet = false
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
        const val ESPERA_DESLIGAR_MS = 2 * 60 * 1000L

        /** Telas que aparecem por cima da Carteira do Google sem que a pessoa tenha saído dela. */
        val PACOTES_IGNORADOS = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.gms",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
        )
    }
}
