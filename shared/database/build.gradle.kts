import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import androidx.room3.gradle.RoomExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.room3)
    alias(libs.plugins.ksp)
}

kotlin {
    android {
        namespace = "dev.agenticscheduler.database"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:application"))
            implementation(project(":shared:sync"))
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
        }
        commonTest.dependencies { implementation(libs.androidx.room3.testing) }
        val desktopTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

extensions.configure<RoomExtension>("room3") {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspCommonMainMetadata", libs.androidx.room3.compiler)
    add("kspDesktop", libs.androidx.room3.compiler)
    add("kspAndroid", libs.androidx.room3.compiler)
}
