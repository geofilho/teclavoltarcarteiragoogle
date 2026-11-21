package com.geofilho.carteirarapidag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.wear.remote.interactions.RemoteActivityHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Monta o relatório de diagnóstico e envia para o GitHub (pelo celular) ou copia. */
object Diagnostico {

    private const val REPO_ISSUES = "https://github.com/geofilho/teclavoltarcarteiragoogle/issues/new"

    fun montar(context: Context): String {
        val pm = context.packageManager
        val versao = try {
            pm.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        // Apps instalados que parecem carteiras ou pagamento (para achar o nome real da Samsung Wallet)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val carteiras = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { p -> listOf("pay", "wallet", "spay").any { p.contains(it, ignoreCase = true) } }
            .sorted()

        val apps = Carteiras.appsDetectados(context)
        val agora = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())

        return buildString {
            appendLine("Diagnóstico Carteira Rápida G")
            appendLine("Data: $agora")
            appendLine("Versão do app: $versao")
            appendLine("Relógio: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Permissão de acessibilidade ativa: ${simNao(Carteiras.servicoAtivo(context))}")
            appendLine("Carteira do Google instalada: ${simNao(Carteiras.googleWalletInstalada(context))}")
            appendLine("NFC desligado: ${simNao(Carteiras.nfcDesligado(context))}")
            appendLine()
            appendLine("Apps de carteira/pagamento instalados:")
            if (carteiras.isEmpty()) appendLine("- nenhum encontrado")
            carteiras.forEach { appendLine("- $it (reconhecido como Samsung Wallet: ${simNao(Carteiras.ehSamsungWallet(it))})") }
            appendLine()
            appendLine("Últimos apps que abriram (mais recente primeiro):")
            if (apps.isEmpty()) appendLine("- nenhum")
            apps.forEach { appendLine("- $it") }
        }
    }

    private fun simNao(valor: Boolean) = if (valor) "sim" else "não"

    fun copiar(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico Carteira Rápida G", montar(context)))
    }

    /**
     * Abre no celular a página do GitHub com o relatório preenchido.
     * [resultado] recebe verdadeiro se o celular recebeu o pedido.
     */
    fun enviarPeloCelular(context: Context, resultado: (Boolean) -> Unit) {
        val texto = montar(context)
        val url = Uri.parse(REPO_ISSUES).buildUpon()
            .appendQueryParameter("title", "Diagnóstico do relógio")
            .appendQueryParameter("body", texto)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val futuro = RemoteActivityHelper(context, executor).startRemoteActivity(intent)
            futuro.addListener({
                val ok = try {
                    futuro.get()
                    true
                } catch (e: Exception) {
                    false
                }
                context.mainExecutor.execute { resultado(ok) }
                executor.shutdown()
            }, executor)
        } catch (e: Exception) {
            executor.shutdown()
            resultado(false)
        }
    }
}
