import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

application {
    mainClass = "dev.agenticscheduler.server.sync.ApplicationKt"
}

dependencies {
    implementation(project(":shared:sync"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
}
