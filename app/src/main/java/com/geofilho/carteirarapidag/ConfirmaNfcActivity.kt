package com.geofilho.carteirarapidag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/** Pergunta curta: "Ativar NFC?" ou "Desativar NFC?", com Sim e Não. */
class ConfirmaNfcActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirma_nfc)

        val ligar = intent.getStringExtra(EXTRA_MODO) != MODO_DESLIGAR
        val pergunta = findViewById<TextView>(R.id.pergunta)
        pergunta.setText(if (ligar) R.string.pergunta_ativar_nfc else R.string.pergunta_desativar_nfc)
        title = pergunta.text

        findViewById<Button>(R.id.btn_sim).setOnClickListener {
            if (ligar) {
                Carteiras.ligarNfcEVoltar(this) // ao ligar, a Carteira do Google abre sozinha
            } else {
                Carteiras.marcarEsperaDesligar(this) // ao desligar, volta para o mostrador
                Carteiras.abrirAjustesNfc(this)
            }
            finish()
        }
        findViewById<Button>(R.id.btn_nao).setOnClickListener {
            if (ligar) Carteiras.abrirGoogleWallet(this)
            finish()
        }
    }

    companion object {
        const val EXTRA_MODO = "modo"
        const val MODO_LIGAR = "ligar"
        const val MODO_DESLIGAR = "desligar"

        fun abrir(context: Context, modo: String) {
            val intent = Intent(context, ConfirmaNfcActivity::class.java)
                .putExtra(EXTRA_MODO, modo)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // nada a fazer
            }
        }
    }
}
