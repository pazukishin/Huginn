plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.huginn"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.huginn"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // La Thor (y el objetivo de la app) es arm64; recortar libVLC ahorra ~60 MB de APK.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Firma de release: keystore local en `.secrets/` (ignorado por git). Si no existe
    // (CI sin secretos) se cae a la firma de debug para poder compilar igualmente.
    val releaseSigningFile = rootProject.file(".secrets/release-signing.properties")
    val signingValues: Map<String, String> = if (releaseSigningFile.isFile) {
        releaseSigningFile.readLines()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
            .toMap()
    } else {
        emptyMap()
    }
    signingConfigs {
        if (signingValues.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(signingValues.getValue("storeFile"))
                storePassword = signingValues.getValue("storePassword")
                keyAlias = signingValues.getValue("keyAlias")
                keyPassword = signingValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // M6: firma propia si `.secrets/release-signing.properties` existe.
            signingConfig = if (signingValues.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // La app es para la AYN Thor (arm64, sin ChromeOS); ABI x86_64 no aplica.
        disable += setOf("ChromeOsAbiSupport")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)
    // Fuerza fragment moderno: una dependencia transitiva trae 1.0.0 y lint lo rechaza
    // junto a registerForActivityResult.
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.vlc)
    implementation(libs.jcifs.ng)
    implementation(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
