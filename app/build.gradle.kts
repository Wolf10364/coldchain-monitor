import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.wolf10364.coldchain"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wolf10364.coldchain"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"

        // 10.0.2.2 is the PC as seen from the emulator. For a real phone pass your PC's address:
        // ./gradlew installDebug -PdashboardUrl=http://192.168.x.x:8000
        val dashboardUrl = providers.gradleProperty("dashboardUrl").getOrElse("http://10.0.2.2:8000")
        buildConfigField("String", "DASHBOARD_URL", "\"$dashboardUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
