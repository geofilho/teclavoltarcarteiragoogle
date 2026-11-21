package com.geofilho.carteirarapidag

import android.accessibilityservice.AccessibilityServiceInfo
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
    private const val ESPERA_NFC_MS = 3 * 60 * 1000L

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
