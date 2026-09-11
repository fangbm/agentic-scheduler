import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    android { namespace = "dev.agenticscheduler.application"; compileSdk = libs.versions.androidCompileSdk.get().toInt(); minSdk = libs.versions.androidMinSdk.get().toInt(); compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    jvm("desktop") { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    sourceSets {
        commonMain.dependencies { api(project(":shared:domain")); api(project(":shared:planner")); api(libs.kotlinx.coroutines.core); api(libs.kotlinx.collections.immutable); api(libs.kotlinx.datetime) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
