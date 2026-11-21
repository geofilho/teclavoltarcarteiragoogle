package com.geofilho.carteirarapidag

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.TextView

/**
 * Sempre que a Samsung Wallet abre (botão Voltar pressionado), troca pela Carteira do Google.
 *
 * O Android 14+ bloqueia apps que abrem telas estando em segundo plano. Por isso o serviço
 * primeiro desenha uma camada própria por cima da Samsung Wallet (camada de acessibilidade,
 * não precisa de permissão extra) e, com ela visível, abre a Carteira do Google.
 *
 * NFC (só quando ele estava desligado):
 * 1. A camada pergunta "Ativar NFC?". Ao ligar, a Carteira do Google abre sozinha.
 * 2. Ao sair da Carteira do Google, a camada pergunta "Desativar NFC?".
 * 3. Ao desligar, volta para o mostrador do relógio.
 */
class BotaoVoltarService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var camada: View? = null

    private var ultimaTroca = 0L
    private var tentativas = 0

    /** A Carteira do Google mandou evento de abertura depois do nosso pedido. */
    private var googleAvisouAbertura = false

    /** A Carteira do Google já apareceu na tela depois que o app ligou o NFC. */
    private var viuGoogleWallet = false

    private val receptorNfc = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val estado = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
            if (estado == NfcAdapter.STATE_ON || estado == NfcAdapter.STATE_OFF) {
                Carteiras.registrar(context, "aviso do sistema: NFC ${if (estado == NfcAdapter.STATE_ON) "ligado" else "desligado"}")
            }
            when (estado) {
                NfcAdapter.STATE_ON -> if (Carteiras.consumirEsperaNfc(context)) {
                    Carteiras.registrar(context, "NFC ligado: abrindo a Carteira do Google")
                    Carteiras.marcarNfcLigadoPeloApp(context, true)
                    viuGoogleWallet = false
                    handler.postDelayed({ abrirCarteiraComCamada() }, ATRASO_NFC_MS)
                }
                NfcAdapter.STATE_OFF -> {
                    Carteiras.marcarNfcLigadoPeloApp(context, false)
                    if (Carteiras.consumirEsperaDesligar(context)) {
                        Carteiras.registrar(context, "NFC desligado: voltando ao mostrador")
                        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, ATRASO_NFC_MS)
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val filtro = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receptorNfc, filtro, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receptorNfc, filtro)
        }
        Carteiras.registrar(this, "serviço iniciado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pacote = event.packageName?.toString() ?: return
        if (pacote == packageName) return // nossas próprias telas e camada
        Carteiras.registrarAppDetectado(this, pacote)

        if (Carteiras.ehSamsungWallet(pacote)) {
            Carteiras.registrar(this, "abriu Samsung Wallet / ${event.className}")
            trocarSamsungWallet()
            return
        }
        if (pacote == Carteiras.GOOGLE_WALLET) {
            if (camada?.tag == TAG_ABRINDO && !googleAvisouAbertura) {
                googleAvisouAbertura = true
                Carteiras.registrar(this, "Carteira do Google avisou abertura; topo=${pacoteDoTopo()}")
            }
            if (Carteiras.nfcLigadoPeloApp(this)) viuGoogleWallet = true
            return
        }
        acompanharSaidaDaCarteira(pacote)
    }

    private fun trocarSamsungWallet() {
        if (!Carteiras.googleWalletInstalada(this)) {
            Carteiras.registrar(this, "Carteira do Google não instalada: nada feito")
            return
        }
        if (camada != null) return // a camada já está cobrindo a Samsung Wallet

        val agora = SystemClock.uptimeMillis()
        val desde = agora - ultimaTroca
        if (desde < MESMA_ABERTURA_MS) return // vários eventos da mesma abertura
        if (desde < JANELA_INSISTIR_MS) {
            if (tentativas >= MAX_TENTATIVAS) {
                Carteiras.registrar(this, "limite de tentativas atingido")
                return
            }
            tentativas++
            Carteiras.registrar(this, "Samsung Wallet voltou: tentativa $tentativas")
        } else {
            tentativas = 0
            val home = performGlobalAction(GLOBAL_ACTION_HOME)
            Carteiras.registrar(this, "troca iniciada (mostrador: ${if (home) "ok" else "falhou"})")
        }
        ultimaTroca = agora

        if (Carteiras.nfcDesligado(this)) mostrarPerguntaLigar() else abrirCarteiraComCamada()
    }

    /** Cobre a tela, abre a Carteira do Google e confere se ela ficou mesmo na frente. */
    private fun abrirCarteiraComCamada() {
        val view = inflar(R.layout.camada_abrindo)
        if (view != null) {
            view.tag = TAG_ABRINDO
            if (!mostrarCamada(view)) Carteiras.registrar(this, "sem camada; abrindo direto")
        }
        googleAvisouAbertura = false
        handler.postDelayed({ pedirGoogleWallet() }, ATRASO_ABRIR_MS)
        handler.postDelayed({ verificar(1) }, VERIFICAR_MS)
    }

    private fun pedirGoogleWallet() {
        val ok = Carteiras.abrirGoogleWallet(this)
        Carteiras.registrar(this, if (ok) "pedido para abrir a Carteira do Google" else "falha ao abrir a Carteira do Google")
    }

    /**
     * Confere qual app está realmente na frente.
     * 1ª vez: se não for a Carteira do Google, pede de novo.
     * 2ª vez: se ainda não for, mostra a pergunta "Abrir Carteira do Google?" (o toque libera a abertura).
     */
    private fun verificar(etapa: Int) {
        if (camada?.tag != TAG_ABRINDO) return
        val topo = pacoteDoTopo()
        Carteiras.registrar(this, "verificação $etapa: topo=$topo; janelas=${descreverJanelas()}")
        val naFrente = topo == Carteiras.GOOGLE_WALLET || (topo == null && googleAvisouAbertura)
        if (naFrente) {
            removerCamada()
            return
        }
        when (etapa) {
            1 -> {
                pedirGoogleWallet()
                handler.postDelayed({ verificar(2) }, VERIFICAR_MS)
            }
            2 -> mostrarPergunta(R.string.pergunta_abrir_carteira, sim = {
                Carteiras.registrar(this, "Abrir Carteira do Google? sim")
                val v = inflar(R.layout.camada_abrindo)
                if (v != null) {
                    v.tag = TAG_ABRINDO
                    mostrarCamada(v)
                }
                googleAvisouAbertura = false
                pedirGoogleWallet()
                handler.postDelayed({ verificar(3) }, VERIFICAR_MS)
            }, nao = {
                Carteiras.registrar(this, "Abrir Carteira do Google? não")
                removerCamada()
            }).also { Carteiras.registrar(this, "mostrou Abrir Carteira do Google?") }
            else -> removerCamada() // última conferência: não deixa a camada presa
        }
    }

    /** Pacote do app que está na frente (janela de aplicativo mais alta). */
    private fun pacoteDoTopo(): String? = try {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }
            ?.root?.packageName?.toString()
    } catch (e: Exception) {
        null
    }

    private fun descreverJanelas(): String = try {
        windows.sortedByDescending { it.layer }.joinToString(" > ") { w ->
            val tipo = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "sistema"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "camada"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "teclado"
                else -> "outro"
            }
            "$tipo:${w.root?.packageName ?: w.title}"
        }
    } catch (e: Exception) {
        "indisponível"
    }

    private fun mostrarPerguntaLigar() {
        mostrarPergunta(R.string.pergunta_ativar_nfc, sim = {
            Carteiras.registrar(this, "Ativar NFC? sim")
            Carteiras.marcarEsperaNfc(this)
            Carteiras.abrirAjustesNfc(this)
            handler.postDelayed({ removerCamada() }, ATRASO_TIRAR_MS)
        }, nao = {
            Carteiras.registrar(this, "Ativar NFC? não")
            removerCamada()
            abrirCarteiraComCamada()
        })
        Carteiras.registrar(this, "mostrou Ativar NFC?")
    }

    private fun mostrarPerguntaDesligar() {
        mostrarPergunta(R.string.pergunta_desativar_nfc, sim = {
            Carteiras.registrar(this, "Desativar NFC? sim")
            Carteiras.marcarEsperaDesligar(this)
            Carteiras.abrirAjustesNfc(this)
            handler.postDelayed({ removerCamada() }, ATRASO_TIRAR_MS)
        }, nao = {
            Carteiras.registrar(this, "Desativar NFC? não")
            removerCamada()
        })
        Carteiras.registrar(this, "mostrou Desativar NFC?")
    }

    private fun mostrarPergunta(texto: Int, sim: () -> Unit, nao: () -> Unit) {
        val view = inflar(R.layout.camada_pergunta) ?: return
        view.tag = TAG_PERGUNTA
        val pergunta = view.findViewById<TextView>(R.id.pergunta)
        pergunta.setText(texto)
        view.findViewById<Button>(R.id.btn_sim).setOnClickListener { sim() }
        view.findViewById<Button>(R.id.btn_nao).setOnClickListener { nao() }
        if (mostrarCamada(view)) {
            // Leitor de tela anuncia a pergunta assim que ela aparece
            pergunta.post { pergunta.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED) }
        }
    }

    /** Só age quando foi o app que ligou o NFC. */
    private fun acompanharSaidaDaCarteira(pacote: String) {
        if (!Carteiras.nfcLigadoPeloApp(this)) return
        if (pacote in PACOTES_IGNORADOS) return
        if (!viuGoogleWallet || Carteiras.nfcDesligado(this)) return

        // Saiu da Carteira do Google: pergunta uma única vez se quer desligar o NFC
        viuGoogleWallet = false
        Carteiras.marcarNfcLigadoPeloApp(this, false)
        Carteiras.registrar(this, "saiu da Carteira do Google para $pacote")
        handler.postDelayed({ mostrarPerguntaDesligar() }, ATRASO_TIRAR_MS)
    }

    private fun inflar(layout: Int): View? = try {
        LayoutInflater.from(ContextThemeWrapper(this, R.style.TemaG)).inflate(layout, null)
    } catch (e: Exception) {
        Carteiras.registrar(this, "erro ao montar a camada: ${e.javaClass.simpleName}")
        null
    }

    private fun mostrarCamada(view: View): Boolean {
        removerCamada()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        return try {
            windowManager?.addView(view, params)
            camada = view
            true
        } catch (e: Exception) {
            Carteiras.registrar(this, "erro ao mostrar a camada: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun removerCamada() {
        val v = camada ?: return
        camada = null
        try {
            windowManager?.removeView(v)
        } catch (e: Exception) {
            // já removida
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removerCamada()
        try {
            unregisterReceiver(receptorNfc)
        } catch (e: Exception) {
            // já não estava registrado
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG_ABRINDO = "abrindo"
        const val TAG_PERGUNTA = "pergunta"

        const val MESMA_ABERTURA_MS = 1_200L
        const val JANELA_INSISTIR_MS = 6_000L
        const val MAX_TENTATIVAS = 3
        const val ATRASO_ABRIR_MS = 150L
        const val ATRASO_TIRAR_MS = 600L
        const val ATRASO_NFC_MS = 400L
        const val VERIFICAR_MS = 1_000L

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
