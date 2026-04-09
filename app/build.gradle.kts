plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val xrayAarFile = layout.projectDirectory.file("libs/xray.aar").asFile
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

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
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

tasks.named("preBuild") {
    dependsOn(verifyXrayAar)
}

dependencies {
    implementation(files("libs/xray.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}