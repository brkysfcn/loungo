plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.phantom"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phantom.browser"
        minSdk = 28
        targetSdk = 36
        versionCode = 13
        versionName = "0.9.11"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("PHANTOM_STORE_FILE").orNull
            val storePass = providers.gradleProperty("PHANTOM_STORE_PASSWORD").orNull
            val keyAliasStr = providers.gradleProperty("PHANTOM_KEY_ALIAS").orNull
            val keyPass = providers.gradleProperty("PHANTOM_KEY_PASSWORD").orNull

            if (!storeFilePath.isNullOrBlank() && !storePass.isNullOrBlank() &&
                !keyAliasStr.isNullOrBlank() && !keyPass.isNullOrBlank()
            ) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = keyAliasStr
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }
    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
            resValue("string", "app_name", "Loungo")
            buildConfigField("int", "WS_PORT", "8765")
            buildConfigField("boolean", "VERBOSE_LOGS", "false")
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".test"
            resValue("string", "app_name", "Loungo TEST")
            buildConfigField("int", "WS_PORT", "8766")
            buildConfigField("boolean", "VERBOSE_LOGS", "true")
        }
    }
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npmCmd = if (isWindows) "npm.cmd" else "npm"

val npmBuild = tasks.register<Exec>("npmBuild") {
    workingDir = file("../pwa")
    commandLine(npmCmd, "run", "build")
    onlyIf { !project.hasProperty("skipPwa") }
}

tasks.named("preBuild") {
    dependsOn(npmBuild)
}

afterEvaluate {
    tasks.findByName("mergeStableReleaseAssets")?.doLast {
        val assetsDir = layout.buildDirectory.dir("intermediates/assets/stableRelease/mergeStableReleaseAssets").get().asFile
        delete(File(assetsDir, "pwa/test"))
        delete(File(assetsDir, "test"))
    }
}

dependencies {
    implementation(libs.androidx.webkit)
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}

gradle.taskGraph.whenReady {
    val hasReleaseTask = allTasks.any { it.name.contains("Release", ignoreCase = true) }
    if (hasReleaseTask) {
        val storeFilePath = providers.gradleProperty("PHANTOM_STORE_FILE").orNull
        val storePass = providers.gradleProperty("PHANTOM_STORE_PASSWORD").orNull
        val keyAliasStr = providers.gradleProperty("PHANTOM_KEY_ALIAS").orNull
        val keyPass = providers.gradleProperty("PHANTOM_KEY_PASSWORD").orNull

        if (storeFilePath.isNullOrBlank() || storePass.isNullOrBlank() ||
            keyAliasStr.isNullOrBlank() || keyPass.isNullOrBlank()
        ) {
            throw GradleException(
                "Release signing configuration missing! " +
                "Please configure PHANTOM_STORE_FILE, PHANTOM_STORE_PASSWORD, " +
                "PHANTOM_KEY_ALIAS, and PHANTOM_KEY_PASSWORD in ~/.gradle/gradle.properties."
            )
        }
    }
}
