import java.util.Properties
import java.io.FileInputStream
import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile: File? = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile!!.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val xrayAarFile = layout.projectDirectory.file("libs/xray.aar").asFile
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// Single source of truth for the app version; reused in defaultConfig and the APK
// output file names (androidComponents block below).
val appVersionName = "3.0.0PRE"

val buildXrayAar by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Builds app/libs/xray.aar from xray-go via gomobile."
    workingDir = rootProject.projectDir

    outputs.upToDateWhen { xrayAarFile.exists() }

    val scriptPath = if (isWindows) {
        "scripts/build-xray-aar.ps1"
    } else {
        "scripts/build-xray-aar.bash"
    }

    if (isWindows) {
        commandLine(
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            scriptPath
        )
    } else {
        commandLine("bash", scriptPath)
    }

    inputs.file(rootProject.file("scripts/build-xray-aar.ps1"))
    inputs.file(rootProject.file("scripts/build-xray-aar.bash"))
    inputs.file(rootProject.file("xray-go/go.mod"))
    inputs.file(rootProject.file("xray-go/xray_bridge.go"))
    outputs.file(xrayAarFile)
}

val verifyXrayAar by tasks.registering {
    group = "verification"
    description = "Ensures app/libs/xray.aar exists before Android build."
    //dependsOn(buildXrayAar)
    doLast {
        if (!xrayAarFile.exists()) {
            throw GradleException(
                "Missing ${xrayAarFile.path}. Run scripts/build-xray-aar.ps1 or scripts/build-xray-aar.bash."
            )
        }
    }
}

android {
    namespace = "com.justme.xtls_core_proxy"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.justme.xtls_core_proxy"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a + armeabi-v7a cover all real phones (old and new). x86_64 is added
            // ONLY so the universal APK (and the AAB) can run natively on x86_64 emulators /
            // Chromebooks — the per-ABI split APKs still carry just their own ABI, so the lean
            // arm64/armv7 downloads are unaffected. (32-bit x86 stays dropped — it's dead.)
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // Per-ABI APK delivery for the F-Droid / direct-download path: assembleRelease emits
    // one APK per ABI (each carrying only its own ~32 MB libgojni.so), so a device
    // downloads ~1/2 the native payload instead of all of it. The Accrescent/AAB path
    // (bundleRelease) splits per-ABI automatically and ignores this block. isUniversalApk
    // also emits one works-everywhere APK as a fallback for plain direct downloads.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    bundle {
        // The app ships an in-app language picker (see docs/features/localization.md) that calls
        // setApplicationLocales/setLocales at runtime. If the App Bundle split resources by language,
        // the chosen locale's strings could be absent at runtime unless downloaded via Play Core.
        // Disabling the language split keeps every locale in the base APK so the picker always works.
        language {
            enableSplit = false
        }
    }
}

// Rename build outputs to boykisser-<abi>-<buildType>-<version>.apk (default is
// app-<abi>-<buildType>.apk). AGP 9 removed the legacy applicationVariants API, so this
// uses androidComponents.onVariants. <abi> is "universal" for the non-split fallback APK.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier ?: "universal"
            (output as com.android.build.api.variant.impl.VariantOutputImpl).outputFileName.set(
                "boykisser-$abi-${variant.buildType}-$appVersionName.apk"
            )
        }
    }
}

// Emit a <apkName>.sha256sum next to each release APK (e.g.
// boykisser-arm64-v8a-release-1.0.2R.apk.sha256sum) on every assembleRelease. Content is
// the standard `sha256sum` line ("<hex>  <filename>"), so users can verify with
// `sha256sum -c <file>.sha256sum` from the output directory.
val sha256ReleaseApks = tasks.register("sha256ReleaseApks") {
    description = "Writes a <name>.sha256sum file next to each release APK."
    group = "verification"
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    doLast {
        val dir = apkDir.get().asFile
        val apks = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: emptyArray()
        apks.forEach { apk ->
            val md = MessageDigest.getInstance("SHA-256")
            apk.inputStream().buffered().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            val hex = md.digest().joinToString("") { b -> "%02x".format(b) }
            File(dir, "${apk.name}.sha256sum").writeText("$hex  ${apk.name}\n")
        }
        logger.lifecycle("sha256ReleaseApks: wrote ${apks.size} .sha256sum file(s) to ${dir.path}")
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(sha256ReleaseApks) }

tasks.named("preBuild") {
    dependsOn(verifyXrayAar)
}

dependencies {
    implementation(files("libs/xray.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation("org.json:json:20260522")
    testImplementation(libs.junit)
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// HealthProbeSchemeTest reads the manifest + network_security_config.xml off the filesystem.
// Without declaring them as inputs, editing either alone leaves testDebugUnitTest UP-TO-DATE and
// the guard silently does not run. (Observed: both mutations reported nothing until --rerun-tasks.)
tasks.withType<Test>().configureEach {
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    inputs.file(layout.projectDirectory.file("src/main/res/xml/network_security_config.xml"))
}

// --- connectedAndroidTest post-run uninstall: OPT-IN -------------------------------------------
// AGP's connectedAndroidTest UNINSTALLS the app-under-test after the run by default, deleting
// /data/data/<pkg> (the app's Room DB + SharedPreferences). On a device holding real user data that
// is a destructive wipe, so the default is flipped to "keep installed" in gradle.properties via
// AGP's injected `android.injected.androidTest.leaveApksInstalledAfterRun=true`.
//
// This block honors a friendly OPT-IN, `-PuninstallAfterTest`, that restores the uninstall. It sets
// the connected-test task's keepInstalledApks input to false (verified in AGP 9.1.1: the runner
// uninstalls iff !testRunnerFactory.keepInstalledApks). That property lives on the task's @Nested
// TestRunnerFactory, so it's reached two hops out: task.getTestRunnerFactory().getKeepInstalledApks().
// The task is matched by class *name* and the property set *reflectively* on purpose: the default
// (no-flag) path never touches this AGP-internal type, so a future AGP rename can't break normal
// builds — and if the reflection ever fails, it fails SAFE (app stays installed, never a surprise wipe).
if (project.hasProperty("uninstallAfterTest")) {
    tasks.matching { it.javaClass.name.contains("DeviceProviderInstrumentTestTask") }
        .configureEach {
            val result = runCatching {
                val factory = javaClass.getMethod("getTestRunnerFactory").invoke(this)
                @Suppress("UNCHECKED_CAST")
                val keep = factory.javaClass.getMethod("getKeepInstalledApks").invoke(factory)
                    as org.gradle.api.provider.Property<Boolean>
                keep.set(false)
            }
            if (result.isSuccess) {
                logger.lifecycle("[uninstallAfterTest] $name: app WILL be uninstalled after the run.")
            } else {
                logger.warn(
                    "[uninstallAfterTest] $name: could NOT override keepInstalledApks " +
                        "(${result.exceptionOrNull()?.message}); app will stay installed (fail-safe)."
                )
            }
        }
}
