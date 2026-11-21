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
 * 1. Abre direto a chave do NFC. Ao ligar, a Carteira do Google abre sozinha.
 * 2. Ao sair da Carteira do Google, abre direto a chave do NFC.
 * 3. Ao desligar, volta para o mostrador do relógio.
 */
class BotaoVoltarService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var camada: View? = null

    private var ultimaTroca = 0L

    /** Momento da última mudança do NFC (a Samsung Wallet liga e desliga o NFC sozinha). */
    private var ultimaMudancaNfc = 0L
    private var tentativas = 0

    /** Último app de verdade na frente (sem contar telas do sistema e as nossas). */
    private var pacoteAnterior: String? = null

    /** A Carteira do Google mandou evento de abertura depois do nosso pedido. */
    private var googleAvisouAbertura = false

    /** A Carteira do Google já apareceu na tela depois que o app ligou o NFC. */
    private var viuGoogleWallet = false

    private val receptorNfc = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val estado = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
            ultimaMudancaNfc = SystemClock.uptimeMillis()
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
        val anterior = pacoteAnterior
        if (pacote !in PACOTES_IGNORADOS) pacoteAnterior = pacote

        if (Carteiras.ehSamsungWallet(pacote)) {
            Carteiras.registrar(this, "abriu Samsung Wallet / ${event.className}")
            val saindoDaCarteira = anterior == Carteiras.GOOGLE_WALLET && camada == null &&
                SystemClock.uptimeMillis() - ultimaTroca > MESMA_ABERTURA_MS
            if (saindoDaCarteira) {
                // A Samsung Wallet só reapareceu porque a pessoa saiu da Carteira do Google
                saiuDaCarteira(pacote)
            } else {
                trocarSamsungWallet()
            }
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
            // "Voltar" fecha a Samsung Wallet. Não usa "mostrador": apertar o início faz o Android
            // bloquear por alguns segundos a abertura de outros apps.
            val voltar = performGlobalAction(GLOBAL_ACTION_BACK)
            Carteiras.registrar(this, "troca iniciada (voltar: ${if (voltar) "ok" else "falhou"})")
        }
        ultimaTroca = agora

        // A Samsung Wallet liga o NFC ao abrir e desliga ao fechar. Cobre a tela e espera o NFC
        // parar de mudar antes de decidir entre abrir a Carteira do Google ou a chave do NFC.
        inflar(R.layout.camada_abrindo)?.let {
            it.tag = TAG_ABRINDO
            mostrarCamada(it)
        }
        handler.postDelayed({ decidirQuandoEstavel() }, ESTABILIZAR_MIN_MS)
    }

    private fun decidirQuandoEstavel() {
        val agora = SystemClock.uptimeMillis()
        val nfcAindaMudando = agora - ultimaMudancaNfc < NFC_PARADO_MS
        if (nfcAindaMudando && agora - ultimaTroca < ESTABILIZAR_MAX_MS) {
            handler.postDelayed({ decidirQuandoEstavel() }, 250L)
            return
        }
        if (Carteiras.nfcDesligado(this)) abrirChaveParaLigar() else abrirCarteiraComCamada()
    }

    /** NFC desligado: abre direto a chave do NFC; ao ligar, a Carteira do Google abre sozinha. */
    private fun abrirChaveParaLigar() {
        Carteiras.marcarEsperaNfc(this)
        val ok = Carteiras.abrirAjustesNfcAutorizado(this) || run {
            Carteiras.abrirAjustesNfc(this)
            false
        }
        Carteiras.registrar(this, "NFC desligado: abrindo a chave do NFC (${if (ok) "ok" else "sem autorização"})")
        handler.postDelayed({ removerCamada() }, ATRASO_TIRAR_MS)
        // Se o NFC já estiver ligado quando a chave abrir, segue para a Carteira do Google
        handler.postDelayed({
            if (!Carteiras.nfcDesligado(this) && Carteiras.consumirEsperaNfc(this)) {
                Carteiras.registrar(this, "NFC já estava ligado na chave: abrindo a Carteira do Google")
                Carteiras.marcarNfcLigadoPeloApp(this, true)
                viuGoogleWallet = false
                abrirCarteiraComCamada()
            }
        }, CONFERIR_CHAVE_MS)
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
        val ok = Carteiras.abrirGoogleWalletAutorizado(this)
        Carteiras.registrar(this, if (ok) "pedido autorizado para abrir a Carteira do Google" else "falha ao abrir a Carteira do Google")
    }

    /**
     * Confere qual app está realmente na frente.
     * 1ª vez: se não for a Carteira do Google, pede de novo.
     * 2ª vez: se ainda não for, mostra a pergunta "Abrir Carteira do Google?" (o toque libera a abertura).
     */
    private fun verificar(etapa: Int, esperas: Int = 0) {
        if (camada?.tag != TAG_ABRINDO) return
        val topo = pacoteDoTopo()
        Carteiras.registrar(this, "verificação $etapa: topo=$topo; janelas=${descreverJanelas()}")
        if (topo == Carteiras.GOOGLE_WALLET) {
            removerCamada()
            // Confere mais uma vez, só para o registro, se ela continuou na frente
            handler.postDelayed({
                Carteiras.registrar(this, "1,5 s depois: topo=${pacoteDoTopo()}")
            }, 1_500L)
            return
        }
        if (topo == null && esperas < MAX_ESPERAS) {
            // Nenhum app na tela ainda (troca em andamento): espera mais um pouco sem repetir
            handler.postDelayed({ verificar(etapa, esperas + 1) }, ESPERA_SEM_APP_MS)
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

    /** Saída para outro app: só interessa quando foi o app que ligou o NFC. */
    private fun acompanharSaidaDaCarteira(pacote: String) {
        if (!Carteiras.nfcLigadoPeloApp(this)) return
        if (pacote in PACOTES_IGNORADOS) return
        if (!viuGoogleWallet) return
        saiuDaCarteira(pacote)
    }

    /**
     * A pessoa saiu da Carteira do Google.
     * - Se foi o app que ligou o NFC: abre direto a chave do NFC; ao desligar, volta ao mostrador.
     * - Senão, se a Samsung Wallet reapareceu por baixo: volta ao mostrador.
     */
    private fun saiuDaCarteira(destino: String) {
        viuGoogleWallet = false
        if (Carteiras.nfcLigadoPeloApp(this) && !Carteiras.nfcDesligado(this)) {
            Carteiras.marcarNfcLigadoPeloApp(this, false)
            Carteiras.marcarEsperaDesligar(this)
            val ok = Carteiras.abrirAjustesNfcAutorizado(this)
            Carteiras.registrar(this, "saiu da Carteira do Google para $destino: abrindo a chave do NFC (${if (ok) "ok" else "falhou"})")
            return
        }
        if (Carteiras.ehSamsungWallet(destino)) {
            Carteiras.registrar(this, "saiu da Carteira do Google e a Samsung Wallet reapareceu: voltando ao mostrador")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
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
        const val ESPERA_SEM_APP_MS = 700L
        const val ESTABILIZAR_MIN_MS = 900L
        const val ESTABILIZAR_MAX_MS = 3_000L
        const val NFC_PARADO_MS = 800L
        const val CONFERIR_CHAVE_MS = 1_500L
        const val MAX_ESPERAS = 4

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
