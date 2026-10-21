import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}
val mapsApiKey = localProperties.getProperty("MAPS_API_KEY", "")
val bluetoothLeftDeviceAddress = localProperties.getProperty("BLUETOOTH_LEFT_DEVICE_ADDRESS", "")
val bluetoothRightDeviceAddress = localProperties.getProperty("BLUETOOTH_RIGHT_DEVICE_ADDRESS", "")

android {
    namespace = "com.example.hapticnav"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.hapticnav"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["googleMapsApiKey"] = mapsApiKey
        buildConfigField("String", "BLUETOOTH_LEFT_DEVICE_ADDRESS", "\"$bluetoothLeftDeviceAddress\"")
        buildConfigField("String", "BLUETOOTH_RIGHT_DEVICE_ADDRESS", "\"$bluetoothRightDeviceAddress\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // For Google Maps
    implementation("com.google.maps.android:maps-compose:2.11.4")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // For Location Services
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // For Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // For Directions API
    implementation("com.google.maps:google-maps-services:2.2.0")
    implementation("com.google.maps.android:maps-utils-ktx:3.4.0")
}