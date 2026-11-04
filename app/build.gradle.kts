plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chessbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chessbot"
        minSdk = 26
        targetSdk = 34
        // растёт с каждой сборкой в CI
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100
        versionName = "1.$versionCode"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Постоянный ключ подписи (лежит в репозитории по решению владельца): все сборки подписаны одним ключом,
    // поэтому приложение обновляется поверх старой версии без удаления.
    signingConfigs {
        create("chessbot") {
            storeFile = file("keystore/chessbot.jks")
            storePassword = "chessbot123"
            keyAlias = "chessbot"
            keyPassword = "chessbot123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("chessbot") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // Stockfish лежит как libstockfish.so и должен быть распакован на диск, чтобы его можно было запустить
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("com.github.bhlangonijr:chesslib:1.3.4")
}
