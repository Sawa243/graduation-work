plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ru.fieldengineer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.fieldengineer.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // адрес сервера «Сервисный пульт»: в отладке эмулятор ходит на компьютер разработчика
        // (10.0.2.2 — это localhost компьютера), в эксплуатации — только HTTPS
        // FLAG_SECURE на экранах с ПДн (Э0, Э3, Э7, Э8): в рабочей сборке включён всегда;
        // в отладочной по умолчанию выключен, чтобы снимать скриншоты с эмулятора для ВКР.
        // Включить в отладке: gradlew assembleDebug -PsecureScreens=true
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:5080/\"")
            val secure = providers.gradleProperty("secureScreens").orNull.toBoolean()
            buildConfigField("boolean", "SECURE_SCREENS", secure.toString())
        }
        release {
            isMinifyEnabled = false
            // заглушка: настоящий адрес сервера подставляется при внедрении
            buildConfigField("String", "API_BASE_URL", "\"https://pult.example.invalid/\"")
            buildConfigField("boolean", "SECURE_SCREENS", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    // схема БД сохраняется в репозитории — по ней видно, как менялись таблицы
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // журнал запросов в Logcat — только в отладочной сборке
    debugImplementation(libs.okhttp.logging)

    testImplementation(libs.junit)
}
