import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android { namespace = "dev.agenticscheduler.application"; compileSdk = libs.versions.androidCompileSdk.get().toInt(); minSdk = libs.versions.androidMinSdk.get().toInt(); compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    jvm("desktop") { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    sourceSets {
        commonMain.dependencies { api(project(":shared:domain")); api(project(":shared:planner")); api(project(":shared:sync")); api(libs.kotlinx.coroutines.core); api(libs.kotlinx.collections.immutable); api(libs.kotlinx.datetime); implementation(libs.ktor.client.core) }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.ktor.client.mock) }
    }
    sourceSets.named("androidMain") { dependencies { implementation(libs.tink.android); implementation(libs.ktor.client.android) } }
    sourceSets.named("desktopMain") { dependencies { implementation(libs.tink); implementation(libs.ktor.client.cio); implementation(libs.jna.platform) } }
    sourceSets.named("desktopTest") {
        dependencies {
            implementation(kotlin("test"))
            implementation(project(":shared:database"))
            implementation(project(":server:sync"))
            implementation(libs.ktor.server.test.host)
            implementation(libs.hikari)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}
