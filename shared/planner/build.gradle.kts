import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    android { namespace = "dev.agenticscheduler.planner"; compileSdk = libs.versions.androidCompileSdk.get().toInt(); minSdk = libs.versions.androidMinSdk.get().toInt(); compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    jvm("desktop") { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    sourceSets {
        commonMain.dependencies { api(project(":shared:domain")); implementation(libs.kotlinx.datetime); implementation(libs.kotlinx.collections.immutable) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
