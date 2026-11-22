plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.bridge"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.bridge"
        minSdk = 30            // Android 11+: Wireless debugging pairing exists from here
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    // Third-party ADB client (Apache-2.0 option). Does pairing + TLS connect to the phone's own adbd.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Own key/certificate generation for the ADB identity
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

