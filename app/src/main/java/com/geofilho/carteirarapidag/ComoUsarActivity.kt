package com.geofilho.carteirarapidag

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView

class ComoUsarActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_como_usar)
        findViewById<Button>(R.id.btn_voltar).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        findViewById<ScrollView>(R.id.rolagem).requestFocus()
    }
}
