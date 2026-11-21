package com.geofilho.carteirarapidag

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
