import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same signing setup as the Habits app — both apps MUST use the same key so they can share the
// signature-level sync permission. CI passes the key via environment variables; locally you can
// put a keystore.properties next to settings.gradle.kts (git-ignored).
val localKeystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ksPath: String? = System.getenv("KEYSTORE_PATH") ?: localKeystore.getProperty("storeFile")
val hasReleaseKey = ksPath != null && file(ksPath).exists()

// The web app (index.html, fonts, icons) lives in the repo root; it is bundled into the APK so the
// app works offline and always matches this commit.
val webOut = layout.buildDirectory.dir("generated/web")
val copyWeb = tasks.register<Sync>("copyWeb") {
    from(rootProject.file("..")) {
        include("index.html", "fonts/**", "icons/**")
    }
    into(webOut.map { it.dir("www") })
}

android {
    namespace = "com.betterlife.liftlog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.betterlife.liftlog"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: localKeystore.getProperty("storePassword")
                keyAlias = System.getenv("KEY_ALIAS") ?: localKeystore.getProperty("keyAlias")
                keyPassword = System.getenv("KEY_PASSWORD") ?: localKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    sourceSets["main"].assets.srcDir(webOut)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
