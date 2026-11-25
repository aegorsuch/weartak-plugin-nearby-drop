import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedLegalAssets = layout.buildDirectory.dir("generated/legalAssets")
val playServicesNearby = "com.google.android.gms:play-services-nearby:19.3.0"
val sdkSource = rootProject.file("build/sdk-source")
val sdkLicense = sdkSource.resolve("LICENSE")
val sdkAdditionalPermission = sdkSource.resolve("LICENSE-ADDITIONAL-PERMISSION.md")
val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}
fun signingValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull
        ?: signingProperties.getProperty(propertyName)

val releaseKeystorePath = signingValue("WEARTAK_RELEASE_KEYSTORE_FILE", "RELEASE_KEYSTORE_FILE")
val releaseStorePassword = signingValue("WEARTAK_RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("WEARTAK_RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("WEARTAK_RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")
val googleNoticeSources by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { googleNoticeSources(playServicesNearby) }

// Google client AARs carry bundled OSS notices that AGP drops from the APK; keep them per module.
val extractGoogleNotices by tasks.registering {
    val out = layout.buildDirectory.dir("generated/googleNotices")
    inputs.files(googleNoticeSources)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        googleNoticeSources.resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group == "com.google.android.gms" && it.file.extension == "aar" }
            .forEach { artifact ->
                val id = artifact.moduleVersion.id
                ZipFile(artifact.file).use { zip ->
                    val entry = zip.getEntry("third_party_licenses.txt") ?: return@use
                    File(dir, "${id.name}-${id.version}-third_party_licenses.txt")
                        .writeBytes(zip.getInputStream(entry).readBytes())
                }
            }
        check(dir.list().orEmpty().isNotEmpty()) { "No Google third-party notices found." }
    }
}

val copyLegalAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    from(rootProject.file("LICENSE-ADDITIONAL-PERMISSION.md")) {
        rename { "LICENSE-ADDITIONAL-PERMISSION.txt" }
    }
    from(sdkLicense) { rename { "WEARTAK-SDK-LICENSE.txt" } }
    from(sdkAdditionalPermission) {
        rename { "WEARTAK-SDK-LICENSE-ADDITIONAL-PERMISSION.txt" }
    }
    from(rootProject.file("NOTICE")) { rename { "THIRD_PARTY_NOTICES.txt" } }
    from(rootProject.file("ci/release-runtime-components.txt")) { rename { "THIRD_PARTY_COMPONENTS.txt" } }
    from(rootProject.file("third_party/Apache-2.0.txt")) { rename { "APACHE-2.0.txt" } }
    from(extractGoogleNotices) { into("third_party") }
    into(generatedLegalAssets)
}

val verifyReleaseNoticeInputs by tasks.registering {
    val runtime = configurations.named("releaseRuntimeClasspath")
    val report = layout.buildDirectory.file("reports/notices/release-runtime-components.txt")
    val reviewedComponents = rootProject.file("ci/release-runtime-components.txt")
    inputs.files(runtime, googleNoticeSources)
    inputs.file(reviewedComponents)
    outputs.file(report)
    dependsOn(extractGoogleNotices, "verifySdk")
    doLast {
        val artifacts = runtime.get().resolvedConfiguration.resolvedArtifacts
        val google = artifacts.filter { it.moduleVersion.id.group == "com.google.android.gms" }
        fun coordinate(artifact: org.gradle.api.artifacts.ResolvedArtifact) =
            artifact.moduleVersion.id.let { "${it.group}:${it.name}:${it.version}" }
        val expected = setOf(
            "com.google.android.gms:play-services-nearby:19.3.0",
            "com.google.android.gms:play-services-base:18.5.0",
            "com.google.android.gms:play-services-basement:18.4.0",
            "com.google.android.gms:play-services-tasks:18.2.0",
        )
        check(google.map(::coordinate).toSet() == expected) {
            "Release Google dependency closure changed; review its notices and additional permission."
        }
        val noticeDir = layout.buildDirectory.dir("generated/googleNotices").get().asFile
        google.forEach { artifact ->
            val id = artifact.moduleVersion.id
            check(File(noticeDir, "${id.name}-${id.version}-third_party_licenses.txt").length() > 0) {
                "Missing notice for ${coordinate(artifact)}."
            }
        }
        val components = artifacts.map(::coordinate).distinct().sorted().joinToString("\n", postfix = "\n")
        val file = report.get().asFile
        file.parentFile.mkdirs()
        file.writeText(components)
        check(reviewedComponents.readText().replace("\r\n", "\n") == components) {
            "Release runtime dependencies changed; review license and notice coverage before updating the inventory."
        }
    }
}

android {
    namespace = "com.weartak.plugin.nearbydrop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weartak.plugin.nearbydrop"
        minSdk = 30
        targetSdk = 34
        val pipelineIid = providers.environmentVariable("CI_PIPELINE_IID").orNull?.toInt()
        if (pipelineIid != null) {
            check(pipelineIid in 0..9999) { "CI_PIPELINE_IID exceeds the supported version range." }
            val commit = providers.environmentVariable("CI_COMMIT_SHORT_SHA").orElse("local").get()
            versionCode = 584 * 10000 + pipelineIid
            versionName = "5.8.4-p$pipelineIid+$commit"
        } else {
            versionCode = 584
            versionName = "5.8.4"
        }
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            if (gradle.extra.has("testRepositoryUser")) {
                it.systemProperty("robolectric.dependency.repo.url", "https://artifacts.tak.gov/artifactory/maven-release")
                it.systemProperty("robolectric.dependency.repo.username", requireNotNull(gradle.extra["testRepositoryUser"]))
                it.systemProperty("robolectric.dependency.repo.password", requireNotNull(gradle.extra["testRepositoryPassword"]))
            }
        }
    }
    lint {
        abortOnError = true
    }
    sourceSets.getByName("main").assets.srcDir(generatedLegalAssets)
    if (!releaseKeystorePath.isNullOrBlank()) {
        check(!releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()) {
            "Incomplete WearTAK production signing configuration."
        }
        val keystore = rootProject.file(releaseKeystorePath)
        check(keystore.isFile) { "WearTAK production keystore not found." }
        signingConfigs.create("wearTakProduction") {
            storeFile = keystore
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
        buildTypes.getByName("release").signingConfig =
            signingConfigs.getByName("wearTakProduction")
    }
}

dependencies {
    implementation(project(":protocol"))
    debugImplementation(files("libs/weartak-plugin-sdk-debug.aar"))
    releaseImplementation(files("libs/weartak-plugin-sdk-release.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(playServicesNearby)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

val verifySdk by tasks.registering {
    val debugSdk = layout.projectDirectory.file("libs/weartak-plugin-sdk-debug.aar")
    val releaseSdk = layout.projectDirectory.file("libs/weartak-plugin-sdk-release.aar")
    inputs.files(debugSdk, releaseSdk, sdkLicense, sdkAdditionalPermission)
    doLast {
        check(debugSdk.asFile.exists() && releaseSdk.asFile.exists() &&
            sdkLicense.isFile && sdkAdditionalPermission.isFile) {
            "Run python ci/fetch_sdk.py and build both pinned SDK variants before building."
        }
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val debugHash = sha256(debugSdk.asFile)
        val releaseHash = sha256(releaseSdk.asFile)
        val isGitLabCi = providers.environmentVariable("GITLAB_CI").orNull == "true"
        val buildContext = if (isGitLabCi) "TPC CI" else "local"
        val expectedDebugHash = if (isGitLabCi) {
            "93064008acda73ccfb0c60ad0ef85bcb7f3f0769446a615a934cb1c437011a50"
        } else {
            "db00bfe9fd98644fbd07161c4b3dfbf65c07513a8197de752fc2a00c59668044"
        }
        val expectedReleaseHash = if (isGitLabCi) {
            "beae3192d0e80d6e1f8da39ffbb20f5e0921c904a34ab44268b9b4b35e04faa1"
        } else {
            "f90a36a6cada1a55a3154944a8b524119217cb95176fd055c2a7dd3aeafaa7a7"
        }
        check(debugHash == expectedDebugHash) {
            "Unexpected SDK debug artifact SHA-256 $debugHash (release SHA-256 $releaseHash); " +
                "expected $expectedDebugHash for $buildContext. Build the pinned source AAR using README.md."
        }
        check(releaseHash == expectedReleaseHash) {
            "Unexpected SDK release artifact SHA-256 $releaseHash (debug SHA-256 $debugHash); " +
                "expected $expectedReleaseHash for $buildContext. Build the pinned source AAR using README.md."
        }
        fun normalizedText(file: File) = file.readText().replace("\r\n", "\n").replace("\r", "\n")
        check(normalizedText(sdkAdditionalPermission) ==
            normalizedText(rootProject.file("LICENSE-ADDITIONAL-PERMISSION.md"))) {
            "Plugin and SDK additional permissions must be identical."
        }
    }
}

tasks.named("preBuild") { dependsOn(verifySdk, copyLegalAssets) }

tasks.register<Copy>("packageDebugArtifact") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/nearbydrop/debug"))
    val pipeline = providers.environmentVariable("CI_PIPELINE_IID").orElse("0").get()
    rename { "WearTAK-Plugin-Nearby-Drop-5.8.4-p$pipeline.apk" }
}

tasks.register<Copy>("packageReleaseArtifact") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(layout.buildDirectory.dir("outputs/nearbydrop/release"))
    val pipeline = providers.environmentVariable("CI_PIPELINE_IID").orElse("0").get()
    rename { "WearTAK-Plugin-Nearby-Drop-5.8.4-p$pipeline.apk" }
}
