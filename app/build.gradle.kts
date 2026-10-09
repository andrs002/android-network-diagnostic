plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.andrs002.networkdiagnostic"
    compileSdk = 35
    defaultConfig { applicationId = "com.andrs002.networkdiagnostic"; minSdk = 29; targetSdk = 35; versionCode = 3; versionName = "0.3" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
