plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.andrs002.networkdiagnostic"
    compileSdk = 35
    defaultConfig { applicationId = "com.andrs002.networkdiagnostic"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
