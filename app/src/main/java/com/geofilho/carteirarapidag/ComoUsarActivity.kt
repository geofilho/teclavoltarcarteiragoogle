package com.geofilho.carteirarapidag

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

class ComoUsarActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_como_usar)
        findViewById<Button>(R.id.btn_voltar).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val apps = Carteiras.appsDetectados(this)
        findViewById<TextView>(R.id.diagnostico).text =
            if (apps.isEmpty()) getString(R.string.diagnostico_vazio)
            else getString(R.string.diagnostico_lista, apps.joinToString("\n"))
        findViewById<ScrollView>(R.id.rolagem).requestFocus()
    }
}
