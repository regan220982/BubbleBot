plugins { id("com.android.application") }

android { namespace = "com.regan.bubblebot"; compileSdk = 36
    defaultConfig { applicationId = "com.regan.bubblebot"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0" }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
}

dependencies { implementation("org.opencv:opencv:4.13.0") }
