plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// google-services.json is generated from the same env vars as the manual Firebase bootstrap,
// so CI/local builds keep working without committing the file.
run {
    val apiKey = envOrProp("FIREBASE_API_KEY")
    val appId = envOrProp("FIREBASE_APP_ID")
    val projectId = envOrProp("FIREBASE_PROJECT_ID")
    val senderId = envOrProp("FIREBASE_SENDER_ID")
    val json = File(projectDir, "google-services.json")
    val valid = apiKey.isNotBlank() && appId.isNotBlank() && projectId.isNotBlank()
    val content = if (valid) {
        """{
          "project_info": { "project_number": "$senderId", "project_id": "$projectId", "storage_bucket": "$projectId.appspot.com" },
          "client": [ {
            "client_info": { "mobilesdk_app_id": "$appId", "android_client_info": { "package_name": "com.mmtube.app" } },
            "oauth_client": [],
            "api_key": [ { "current_key": "$apiKey" } ],
            "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
          } ],
          "configuration_version": "1"
        }"""
    } else {
        """{
          "project_info": { "project_number": "0", "project_id": "placeholder" },
          "client": [ {
            "client_info": { "mobilesdk_app_id": "1:0:android:0", "android_client_info": { "package_name": "com.mmtube.app" } },
            "oauth_client": [],
            "api_key": [ { "current_key": "placeholder" } ],
            "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
          } ],
          "configuration_version": "1"
        }"""
    }
    if (!json.exists() || json.readText() != content) {
        json.writeText(content)
    }
}

fun envOrProp(name: String, default: String = ""): String {
    val value = System.getenv(name) ?: (project.findProperty(name) as String?)
    return value?.takeIf { it.isNotBlank() } ?: default
}

fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 22
val appVersionName = (project.findProperty("versionName") as String?) ?: "1.6.0"
val keystorePath = envOrProp("KEYSTORE_PATH", "keystore/mmtube-demo.p12")

android {
    namespace = "com.mmtube.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mmtube.app"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "FIREBASE_API_KEY", quoted(envOrProp("FIREBASE_API_KEY")))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(envOrProp("FIREBASE_APP_ID")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(envOrProp("FIREBASE_PROJECT_ID")))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(envOrProp("FIREBASE_SENDER_ID")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(envOrProp("GOOGLE_WEB_CLIENT_ID")))
        buildConfigField("String", "UPDATE_REPO", quoted(envOrProp("UPDATE_REPO")))
        buildConfigField(
            "String",
            "SUPPORT_CONTACT",
            quoted(envOrProp("SUPPORT_CONTACT", "https://t.me/TKA_MTC"))
        )
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
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
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
    implementation("androidx.compose.animation:animation")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Jackson is only a runtime dep of youtubedl-android; needed on the compile classpath.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // For Unix symlink entries in the FFmpeg bundle (mirrors upstream ZipUtils)
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-crashlytics")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")

    // yt-dlp + Python + aria2c (youtubedl-android). FFmpeg 9.0.2 (Termux build)
    // is fetched and repacked by tools/ffmpeg/fetch-termux-ffmpeg.sh into jniLibs.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
