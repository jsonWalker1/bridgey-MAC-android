plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = System.getenv("BRIDGEY_ANDROID_KEYSTORE")
val releaseStorePassword = System.getenv("BRIDGEY_ANDROID_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("BRIDGEY_ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("BRIDGEY_ANDROID_KEY_PASSWORD")

android {
    namespace = "dev.bridgey.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bridgey.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "0.6.0-rc.2"
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // BRIDGEY SMB POC: jfileserver's transitive deps (JNA, bouncycastle, guava) duplicate a few
    // META-INF license/notice files. DELETE this block alongside the POC.
    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:discovery"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // BRIDGEY SMB POC (feasibility spike only - see SmbPocServer.kt/SmbPocActivity.kt/SmbPocService.kt).
    // DELETE this whole block, the JitPack repo in settings.gradle.kts, and the three Smb*.kt files
    // to fully remove the POC. org.filesys/jfileserver's only Android-friendly build is this fork.
    implementation("com.github.buttercookie42:jfileserver:ff550a7") {
        exclude(group = "com.hazelcast", module = "hazelcast")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation("org.bouncycastle:bcprov-jdk15to18:1.79")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    implementation("com.google.guava:guava:33.4.0-android")
    implementation("org.apache.commons:commons-lang3:3.17.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for local JVM unit tests - the bundled android.jar stub throws
    // "not mocked" for JSONObject.put/opt* outside instrumented/Robolectric tests.
    testImplementation("org.json:json:20231013")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("bridgey.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
