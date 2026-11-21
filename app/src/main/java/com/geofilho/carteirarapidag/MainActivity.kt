package com.geofilho.carteirarapidag

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var rolagem: ScrollView
    private lateinit var status: TextView
    private lateinit var btnAtivar: Button
    private lateinit var btnInstalar: Button
    private lateinit var btnTestar: Button
    private lateinit var btnNfc: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rolagem = findViewById(R.id.rolagem)
        status = findViewById(R.id.status)
        btnAtivar = findViewById(R.id.btn_ativar)
        btnInstalar = findViewById(R.id.btn_instalar)
        btnTestar = findViewById(R.id.btn_testar)
        btnNfc = findViewById(R.id.btn_nfc)

        btnAtivar.setOnClickListener { abrirAjustesAcessibilidade() }
        btnInstalar.setOnClickListener { Carteiras.abrirLojaGoogleWallet(this) }
        btnTestar.setOnClickListener { Carteiras.abrirGoogleWallet(this) }
        btnNfc.setOnClickListener { Carteiras.ligarNfcEVoltar(this) }
        val aviso = findViewById<TextView>(R.id.aviso_diag)
        fun avisar(texto: Int) {
            aviso.setText(texto)
            aviso.visibility = View.VISIBLE
        }
        val swDiag = findViewById<Switch>(R.id.sw_diagnostico)
        swDiag.isChecked = Carteiras.diagnosticoLigado(this)
        swDiag.setOnCheckedChangeListener { _, ligado ->
            Carteiras.definirDiagnostico(this, ligado)
            avisar(if (ligado) R.string.diag_ligado else R.string.diag_desligado)
        }
        findViewById<Button>(R.id.btn_enviar_diag).setOnClickListener {
            avisar(R.string.diag_enviando)
            Diagnostico.enviarPeloCelular(this) { ok ->
                avisar(if (ok) R.string.diag_enviado else R.string.diag_falhou)
            }
        }
        findViewById<Button>(R.id.btn_copiar_diag).setOnClickListener {
            Diagnostico.copiar(this)
            avisar(R.string.diag_copiado)
        }
        findViewById<Button>(R.id.btn_como_usar).setOnClickListener {
            startActivity(Intent(this, ComoUsarActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Se voltou para cá depois de ligar o NFC (caso o serviço não tenha aberto antes), segue para a Carteira
        if (!Carteiras.nfcDesligado(this) && Carteiras.consumirEsperaNfc(this) &&
            Carteiras.abrirGoogleWallet(this)
        ) {
            Carteiras.marcarNfcLigadoPeloApp(this, true)
            return
        }
        atualizar()
        // Permite rolar a tela girando a moldura do relógio
        rolagem.requestFocus()
    }

    private fun atualizar() {
        val temGoogle = Carteiras.googleWalletInstalada(this)
        val ativo = Carteiras.servicoAtivo(this)
        val nfcDesligado = Carteiras.nfcDesligado(this)

        status.setText(
            when {
                !temGoogle -> R.string.status_sem_google
                !ativo -> R.string.status_falta_permissao
                nfcDesligado -> R.string.status_nfc_desligado
                else -> R.string.status_pronto
            }
        )
        btnInstalar.visibility = if (temGoogle) View.GONE else View.VISIBLE
        btnAtivar.visibility = if (ativo) View.GONE else View.VISIBLE
        btnNfc.visibility = if (nfcDesligado) View.VISIBLE else View.GONE
        btnTestar.visibility = if (temGoogle) View.VISIBLE else View.GONE
    }

    private fun abrirAjustesAcessibilidade() {
        val componente = ComponentName(this, BotaoVoltarService::class.java).flattenToString()
        val tentativas = listOf(
            // Vai direto para a tela do app, quando o relógio permite
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, componente),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tentativas) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // tenta a próxima
            }
        }
    }
}
