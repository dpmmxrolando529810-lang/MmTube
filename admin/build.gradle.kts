plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun envOrProp(name: String, default: String = ""): String {
    val value = System.getenv(name) ?: (project.findProperty(name) as String?)
    return value?.takeIf { it.isNotBlank() } ?: default
}

fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 20
val appVersionName = (project.findProperty("versionName") as String?) ?: "1.4.0"
val keystorePath = envOrProp("KEYSTORE_PATH", "keystore/mmtube-demo.p12")

android {
    namespace = "com.mmtube.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mmtube.admin"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "FIREBASE_API_KEY", quoted(envOrProp("FIREBASE_API_KEY")))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(envOrProp("FIREBASE_ADMIN_APP_ID", envOrProp("FIREBASE_APP_ID"))))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(envOrProp("FIREBASE_PROJECT_ID")))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(envOrProp("FIREBASE_SENDER_ID")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(envOrProp("GOOGLE_WEB_CLIENT_ID")))
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystorePath)
            storePassword = envOrProp("KEYSTORE_PASSWORD", "mmtube")
            keyAlias = envOrProp("KEY_ALIAS", "mmtube")
            keyPassword = envOrProp("KEY_PASSWORD", "mmtube")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
