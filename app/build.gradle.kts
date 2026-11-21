plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.geofilho.carteirarapidag"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geofilho.carteirarapidag"
        // Wear OS 3 ou mais novo (Galaxy Watch 4 em diante, incluindo o Galaxy Watch Ultra 2)
        minSdk = 30
        targetSdk = 35
        versionCode = 9
        versionName = "1.8"
    }

    // Chave fixa no repositório: assim cada nova versão instala por cima da anterior
    // sem precisar desinstalar. É uma chave só para uso pessoal (instalação manual).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Abre no celular a página do GitHub com o diagnóstico preenchido
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
}
