plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.cosc3001"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.cosc3001"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Read Supabase config from gradle properties with environment fallback
    val supabaseUrl = providers.gradleProperty("SUPABASE_URL")
        .orElse(providers.environmentVariable("SUPABASE_URL"))
        .orNull ?: ""
    val supabaseAnonKey = providers.gradleProperty("SUPABASE_ANON_KEY")
        .orElse(providers.environmentVariable("SUPABASE_ANON_KEY"))
        .orNull ?: ""
    // New: Gemini API Key + optional model name (defaults to gemini-2.0-flash)
    val geminiApiKey = providers.gradleProperty("GEMINI_API_KEY")
        .orElse(providers.environmentVariable("GEMINI_API_KEY"))
        .orNull ?: ""
    val geminiModel = providers.gradleProperty("GEMINI_MODEL")
        .orElse(providers.environmentVariable("GEMINI_MODEL"))
        .orNull ?: "gemini-2.0-flash"

    buildTypes {
        debug {
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
            buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
            buildConfigField("String", "GEMINI_MODEL", "\"$geminiModel\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
            buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
            buildConfigField("String", "GEMINI_MODEL", "\"$geminiModel\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
            // Added to include existing png under src/assets as an Android asset folder
            assets.srcDirs("src/assets")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // AppCompat for AppCompatActivity
    implementation(libs.androidx.appcompat)

    // ARCore
    implementation(libs.core)

    // SceneView
    implementation(libs.arsceneview)
    implementation(libs.sceneview)

    // ZXing for QR code scanning
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    // OpenCV for pose estimation
    implementation(project(":sdk"))
    implementation(libs.litert)

    // Supabase-KT via BOM and modules (use direct coordinates here to avoid catalog issues)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.4"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    // Ktor client deps (core + engine + logging + content negotiation + serialization JSON) at 3.3.0
    implementation("io.ktor:ktor-client-core:3.3.0")
    implementation("io.ktor:ktor-client-okhttp:3.3.0")
    implementation("io.ktor:ktor-client-logging:3.3.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Kotlin serialization JSON (fallback parsing)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
