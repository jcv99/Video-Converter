import org.videoconverter.release.ReleaseVersion

plugins {
    id("com.android.application") version "9.5.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

// CI and downstream packagers build with the same version derived from a release tag.
val releaseTag = providers.environmentVariable("RELEASE_TAG")
    .orElse(providers.gradleProperty("releaseTag")).orNull
val releaseVersion = releaseTag?.let { ReleaseVersion.parse(it) }
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull

android {
    namespace = "org.videoconverter.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.videoconverter.app"
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "org.videoconverter.app.ConversionMatrixTest"
        versionCode = releaseVersion?.code ?: 1
        versionName = releaseVersion?.name ?: "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("native/jniLibs").get().asFile.path)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // kotlinc -Wextra  (K2 extra checks)
        extraWarnings.set(true)
        // kotlinc -Werror
        allWarningsAsErrors.set(true)
        // kotlinc -progressive (strict future language rules)
        progressiveMode.set(true)
        // pin language/API level
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        // kotlinc -opt-in
        optIn.add("kotlin.time.ExperimentalTime")
        // verbose logging
        verbose.set(true)
        freeCompilerArgs.addAll(
            listOf(
                "-Xexplicit-api=strict",     // pedantic: explicit public API
                "-Xjdk-release=17",          // JDK API surface pin
                "-Xjsr305=strict",           // nullability annotations strict
                "-Xwarning-level=UNUSED_VARIABLE:disabled", // mute one warning
                "-Xreport-all-warnings",
                "-Xrender-internal-diagnostic-names",
                "-Xtype-enhancement-improvements-strict-mode",
            )
        )
    }
}

val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
val nativeNdk = sdkDirectory.map { it.dir("ndk/${android.ndkVersion}") }
val nativeApi = android.defaultConfig.minSdk!!
val buildNative = tasks.register<Exec>("buildNative") {
    inputs.files(rootProject.file("native/converter.c"), rootProject.file("scripts/build-native.sh"),
        nativeNdk.map { it.file("source.properties") })
    inputs.property("androidApi", nativeApi)
    outputs.dir(layout.buildDirectory.dir("native/jniLibs"))
    commandLine("bash", rootProject.file("scripts/build-native.sh"),
        nativeNdk.get().asFile, layout.buildDirectory.dir("native").get().asFile, nativeApi)
}
tasks.named("preBuild") { dependsOn(buildNative) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
}
