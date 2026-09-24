import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    android {
        namespace = "dev.agenticscheduler.agent"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    jvm("desktop") { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
