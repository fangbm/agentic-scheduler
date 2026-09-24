plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.agenticscheduler.wear"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.agenticscheduler.wear"
        minSdk = 30
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["d8SyncBaseUrl"] = providers.gradleProperty("d8SyncBaseUrl").orElse("").get()
        manifestPlaceholders["d8SyncAccountId"] = providers.gradleProperty("d8SyncAccountId").orElse("").get()
    }
}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:application"))
    implementation(project(":shared:database"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
