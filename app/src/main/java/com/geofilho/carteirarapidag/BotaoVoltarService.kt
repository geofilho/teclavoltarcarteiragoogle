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
import android.view.accessibility.AccessibilityEvent

/**
 * Sempre que a Samsung Wallet abre (botão Voltar pressionado), troca pela Carteira do Google.
 *
 * NFC (só quando ele estava desligado):
 * 1. Pergunta "Ativar NFC?". Ao ligar, a Carteira do Google abre sozinha.
 * 2. Ao sair da Carteira do Google, pergunta "Desativar NFC?".
 * 3. Ao desligar, volta para o mostrador do relógio.
 * Se o NFC já estava ligado, nada disso aparece.
 */
class BotaoVoltarService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var ultimaTroca = 0L

    /** A Carteira do Google já apareceu na tela depois que o app ligou o NFC. */
    private var viuGoogleWallet = false

    private val receptorNfc = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)) {
                NfcAdapter.STATE_ON -> if (Carteiras.consumirEsperaNfc(context)) {
                    Carteiras.marcarNfcLigadoPeloApp(context, true)
                    viuGoogleWallet = false
                    handler.postDelayed({ Carteiras.abrirGoogleWallet(context) }, ATRASO_NFC_MS)
                }
                NfcAdapter.STATE_OFF -> {
                    Carteiras.marcarNfcLigadoPeloApp(context, false)
                    if (Carteiras.consumirEsperaDesligar(context)) {
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pacote = event.packageName?.toString() ?: return
        if (pacote != packageName) Carteiras.registrarAppDetectado(this, pacote)

        if (Carteiras.ehSamsungWallet(pacote)) {
            trocarSamsungWallet()
            return
        }
        acompanharSaidaDaCarteira(pacote)
    }

    private fun trocarSamsungWallet() {
        val agora = SystemClock.uptimeMillis()
        if (agora - ultimaTroca < INTERVALO_MINIMO_MS) return
        if (!Carteiras.googleWalletInstalada(this)) return // sem ela, deixa a Samsung Wallet abrir
        ultimaTroca = agora

        // Manda a Samsung Wallet para trás; assim, ao sair da Carteira do Google, ela não reaparece
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            if (Carteiras.nfcDesligado(this)) {
                ConfirmaNfcActivity.abrir(this, ConfirmaNfcActivity.MODO_LIGAR)
            } else {
                Carteiras.abrirGoogleWallet(this)
            }
        }, ATRASO_TROCA_MS)
    }

    /** Só age quando foi o app que ligou o NFC. */
    private fun acompanharSaidaDaCarteira(pacote: String) {
        if (!Carteiras.nfcLigadoPeloApp(this)) return
        if (pacote == Carteiras.GOOGLE_WALLET) {
            viuGoogleWallet = true
            return
        }
        if (pacote in PACOTES_IGNORADOS || pacote == packageName) return
        if (!viuGoogleWallet || Carteiras.nfcDesligado(this)) return

        // Saiu da Carteira do Google: pergunta uma única vez se quer desligar o NFC
        viuGoogleWallet = false
        Carteiras.marcarNfcLigadoPeloApp(this, false)
        handler.postDelayed({
            ConfirmaNfcActivity.abrir(this, ConfirmaNfcActivity.MODO_DESLIGAR)
        }, ATRASO_TROCA_MS)
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
        const val INTERVALO_MINIMO_MS = 2_000L
        const val ATRASO_TROCA_MS = 250L
        const val ATRASO_NFC_MS = 400L

        /** Telas que aparecem por cima da Carteira do Google sem que a pessoa tenha saído dela. */
        val PACOTES_IGNORADOS = setOf(
            "com.android.systemui",
            "com.samsung.android.wearable.sysui",
            "com.google.android.apps.wearable.systemui",
            "android",
            "com.google.android.gms",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
        )
    }
}
