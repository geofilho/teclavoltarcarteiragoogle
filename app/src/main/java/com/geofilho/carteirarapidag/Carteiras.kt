package com.geofilho.carteirarapidag

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** Tudo o que o app precisa saber sobre as duas carteiras. */
object Carteiras {

    const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"

    /** Pacotes conhecidos da Samsung Wallet / Samsung Pay (relógio e celular). */
    private val SAMSUNG_WALLET = setOf(
        "com.samsung.android.samsungpay.gear",
        "com.samsung.android.spay",
        "com.samsung.android.spaymini",
        "com.samsung.android.samsungpay",
    )

    fun ehSamsungWallet(pacote: String?): Boolean {
        if (pacote.isNullOrEmpty()) return false
        if (pacote in SAMSUNG_WALLET) return true
        // Rede de segurança caso a Samsung mude o nome do pacote numa atualização
        return pacote.startsWith("com.samsung") &&
            (pacote.contains("samsungpay") || pacote.contains(".spay") || pacote.contains("wallet"))
    }

    fun intentGoogleWallet(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(GOOGLE_WALLET)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

    /**
     * Abre uma tela por um PendingIntent com a autorização explícita de abertura em segundo plano
     * exigida pelo Android 14+ (o app tem o privilégio por ser serviço de acessibilidade, mas o
     * Android só o usa quando o app declara isso).
     */
    fun abrirAutorizado(context: Context, intent: Intent, codigo: Int): Boolean = try {
        val modo = if (Build.VERSION.SDK_INT >= 36) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val criador = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) criador.setPendingIntentCreatorBackgroundActivityStartMode(modo)
        val pendente = PendingIntent.getActivity(
            context, codigo, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            criador.toBundle(),
        )
        val envio = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) envio.setPendingIntentBackgroundActivityStartMode(modo)
        pendente.send(context, 0, null, null, null, null, envio.toBundle())
        true
    } catch (e: Exception) {
        registrar(context, "abertura autorizada falhou: ${e.javaClass.simpleName}")
        false
    }

    fun abrirGoogleWalletAutorizado(context: Context): Boolean {
        val intent = intentGoogleWallet(context) ?: return false
        return abrirAutorizado(context, intent, 7) || abrirGoogleWallet(context)
    }

    /** Abre a chave do NFC com a abertura autorizada (usada pelo serviço, em segundo plano). */
    fun abrirAjustesNfcAutorizado(context: Context): Boolean {
        val pm = context.packageManager
        val intent = listOf(
            Intent(Settings.ACTION_NFC_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        ).firstOrNull { it.resolveActivity(pm) != null } ?: return false
        return abrirAutorizado(context, intent, 8)
    }

    fun googleWalletInstalada(context: Context): Boolean = intentGoogleWallet(context) != null

    fun abrirGoogleWallet(context: Context): Boolean {
        val intent = intentGoogleWallet(context) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun abrirLojaGoogleWallet(context: Context) {
        val loja = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_WALLET"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(loja)
        } catch (e: Exception) {
            // Sem Play Store acessível: não há o que fazer além de avisar na tela
        }
    }

    /** Verdadeiro se o relógio tem NFC e ele está desligado. Apps comuns não podem ligar o NFC. */
    fun nfcDesligado(context: Context): Boolean {
        val nfc = NfcAdapter.getDefaultAdapter(context) ?: return false
        return !nfc.isEnabled
    }

    private const val PREFS = "carteira_rapida_g"
    private const val CHAVE_ESPERANDO_NFC = "esperando_nfc_desde"
    private const val ESPERA_NFC_MS = 2 * 60 * 1000L

    /** Lembra que, assim que o NFC for ligado, a Carteira do Google deve abrir sozinha. */
    fun marcarEsperaNfc(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(CHAVE_ESPERANDO_NFC, System.currentTimeMillis()).apply()
    }

    /** Devolve verdadeiro (e limpa o aviso) se a pessoa foi ligar o NFC há pouco tempo. */
    fun consumirEsperaNfc(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val desde = prefs.getLong(CHAVE_ESPERANDO_NFC, 0L)
        if (desde == 0L) return false
        prefs.edit().remove(CHAVE_ESPERANDO_NFC).apply()
        return System.currentTimeMillis() - desde < ESPERA_NFC_MS
    }

    private const val CHAVE_SESSAO_NFC = "nfc_ligado_pelo_app"
    private const val CHAVE_ESPERANDO_DESLIGAR = "esperando_desligar_desde"
    private const val CHAVE_APPS_DETECTADOS = "apps_detectados"

    /** Lembra que, assim que o NFC for desligado, o app deve voltar para o mostrador. */
    fun marcarEsperaDesligar(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(CHAVE_ESPERANDO_DESLIGAR, System.currentTimeMillis()).apply()
    }

    fun consumirEsperaDesligar(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val desde = prefs.getLong(CHAVE_ESPERANDO_DESLIGAR, 0L)
        if (desde == 0L) return false
        prefs.edit().remove(CHAVE_ESPERANDO_DESLIGAR).apply()
        return System.currentTimeMillis() - desde < ESPERA_NFC_MS
    }

    private const val CHAVE_DIAGNOSTICO = "diagnostico_ligado"

    /** Coleta de diagnóstico (registro de eventos e últimos apps). Ligada por padrão. */
    fun diagnosticoLigado(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CHAVE_DIAGNOSTICO, true)

    /** Liga ou desliga a coleta. Ao desligar, apaga tudo o que foi coletado. */
    fun definirDiagnostico(context: Context, ligado: Boolean) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CHAVE_DIAGNOSTICO, ligado)
        if (!ligado) editor.remove(CHAVE_APPS_DETECTADOS).remove(CHAVE_REGISTRO)
        editor.apply()
    }

    /** Guarda os últimos apps que apareceram na tela, para diagnóstico na tela Como usar. */
    fun registrarAppDetectado(context: Context, pacote: String) {
        if (!diagnosticoLigado(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lista = appsDetectados(context)
        if (lista.firstOrNull() == pacote) return
        val nova = (listOf(pacote) + lista.filter { it != pacote }).take(6)
        prefs.edit().putString(CHAVE_APPS_DETECTADOS, nova.joinToString("\n")).apply()
    }

    private const val CHAVE_REGISTRO = "registro_eventos"

    /** Registro com horário dos últimos eventos, para o diagnóstico. */
    fun registrar(context: Context, linha: String) {
        if (!diagnosticoLigado(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hora = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
        val atual = registro(context)
        val nova = (atual + "$hora $linha").takeLast(40)
        prefs.edit().putString(CHAVE_REGISTRO, nova.joinToString("\n")).apply()
    }

    fun registro(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHAVE_REGISTRO, "")!!
            .split("\n").filter { it.isNotBlank() }

    fun appsDetectados(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHAVE_APPS_DETECTADOS, "")!!
            .split("\n").filter { it.isNotBlank() }

    /**
     * Marca que o NFC foi ligado por causa do app (ele estava desligado antes).
     * Só nesse caso, ao sair da Carteira do Google, o app oferece desligar o NFC de novo.
     */
    fun marcarNfcLigadoPeloApp(context: Context, ligado: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CHAVE_SESSAO_NFC, ligado).apply()
    }

    fun nfcLigadoPeloApp(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CHAVE_SESSAO_NFC, false)

    /** Abre a chave do NFC e, quando ele for ligado, a Carteira do Google abre sozinha. */
    fun ligarNfcEVoltar(context: Context) {
        marcarEsperaNfc(context)
        abrirAjustesNfc(context)
    }

    /** Abre a tela onde a pessoa liga o NFC. */
    fun abrirAjustesNfc(context: Context) {
        val tentativas = listOf(
            Intent(Settings.ACTION_NFC_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tentativas) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // tenta a próxima
            }
        }
    }

    fun servicoAtivo(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val meu = ComponentName(context, BotaoVoltarService::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val si = info.resolveInfo.serviceInfo
                si.packageName == meu.packageName && si.name == meu.className
            }
    }
}
