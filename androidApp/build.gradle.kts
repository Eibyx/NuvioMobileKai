import java.util.Properties
import javax.inject.Inject

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

/** Copies one native library, for every ABI, out of an AAR into a jniLibs-shaped directory. */
abstract class ExtractAarNativeLibrary @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles
    abstract val aar: ConfigurableFileCollection

    @get:Input
    abstract val libraryName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        files.sync {
            from(archives.zipTree(aar.singleFile)) {
                include("jni/*/${libraryName.get()}")
                eachFile { path = path.removePrefix("jni/") }
            }
            includeEmptyDirs = false
            into(outputDirectory)
        }
    }
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sentry.android.gradle)
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseStoreFile = localProps.getProperty("NUVIO_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = localProps.getProperty("NUVIO_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = localProps.getProperty("NUVIO_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = localProps.getProperty("NUVIO_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = releaseStoreFile?.let(rootProject::file)
fun envOrLocalProperty(key: String): String? =
    providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

val sentryAuthToken = envOrLocalProperty("SENTRY_AUTH_TOKEN")
val sentryOrg = envOrLocalProperty("SENTRY_ORG")
val sentryProject = envOrLocalProperty("SENTRY_PROJECT")
val sentryMappingUploadEnabled = sentryAuthToken != null && sentryOrg != null && sentryProject != null
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = providers.gradleProperty("nuvio.app.versionName").orNull
    ?: readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
// Kai's versionCode comes from its own MARKETING_VERSION (major * 1_000_000 + minor * 1_000 + patch)
// rather than upstream's CURRENT_PROJECT_VERSION, so merging upstream build numbers never lowers it.
val releaseAppVersionCode = Regex("""^(\d+)\.(\d+)\.(\d+)""")
    .find(releaseAppVersionName.trim().removePrefix("v"))
    ?.destructured
    ?.let { (major, minor, patch) ->
        check(minor.toInt() < 1_000 && patch.toInt() < 1_000) {
            "Minor and patch must stay below 1000 to derive the versionCode from $releaseAppVersionName"
        }
        major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
    }
    ?: error("Version name $releaseAppVersionName does not start with major.minor.patch")
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
// CI passes -Pnuvio.android.signDebugWithRelease=true so every debug APK carries the same key and
// installs over the previous one; locally the debug keystore is used as usual.
val signDebugWithRelease = providers.gradleProperty("nuvio.android.signDebugWithRelease")
    .map(String::toBooleanStrict)
    .getOrElse(false)
val buildsReleaseApks = requestedTaskNames.any {
    it.startsWith("assemble", ignoreCase = true) && it.endsWith("Release", ignoreCase = true)
}
// -Pnuvio.android.abis=arm64-v8a (comma separated) builds only those ABIs; without it every
// supported ABI is built. Release APKs are split per ABI, other builds keep just the listed ones.
val supportedAndroidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val requestedAndroidAbis = providers.gradleProperty("nuvio.android.abis").orNull
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.also { abis ->
        check(abis.isNotEmpty() && abis.all(supportedAndroidAbis::contains)) {
            "nuvio.android.abis must list ABIs from $supportedAndroidAbis, got $abis"
        }
    }
val androidAbis = requestedAndroidAbis ?: supportedAndroidAbis

android {
    namespace = "com.nuvio.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.eibyx.nuviokai"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ABI splits and abiFilters cannot be combined, so the filter only applies when not splitting.
        if (requestedAndroidAbis != null && !buildsReleaseApks) {
            ndk { abiFilters += requestedAndroidAbis }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }

    sourceSets.getByName("full") {
        manifest.srcFile("src/full/AndroidManifest.xml")
        jniLibs.directories.add("../composeApp/src/full/jniLibs")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }

    androidResources {
        noCompress += "cvr"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = buildsReleaseApks
            reset()
            include(*androidAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    buildTypes {
        getByName("release") {
            val minifyRelease = providers.gradleProperty("releaseMinifyEnabled")
                .map(String::toBooleanStrict)
                .getOrElse(true)
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../composeApp/proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        getByName("debug") {
            if (signDebugWithRelease) {
                check(releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                    "nuvio.android.signDebugWithRelease needs the NUVIO_RELEASE_* signing properties in local.properties"
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// libmpv from mpvlibAndroid is built with a newer NDK than the other native dependencies and needs symbols only its own
// libc++_shared.so has. pickFirsts keeps whichever copy it meets first, so the app ships mpvlib's
// copy itself: the app's own native libraries win over those of its dependencies.
val mpvlibAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val extractMpvlibCxxShared by tasks.registering(ExtractAarNativeLibrary::class) {
    aar.from(mpvlibAar)
    libraryName.set("libc++_shared.so")
    outputDirectory.set(layout.buildDirectory.dir("generated/mpvlib-libcxx/jniLibs"))
}

@CacheableTask
abstract class CargoNdkBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val crate: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Internal
    abstract val ndk: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @TaskAction
    fun build() {
        val out = jniLibs.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        exec.exec {
            workingDir = workspace.get().asFile
            environment("ANDROID_NDK_HOME", ndk.get().asFile.path)
            commandLine(
                listOf(cargo(), "ndk", "--platform", minSdk.get().toString()) +
                    abis.get().flatMap { listOf("-t", it) } +
                    listOf("-o", out.path, "build", "--release", "-p", crate.get()),
            )
        }
    }

    private fun cargo(): String {
        val home = System.getenv("CARGO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cargo")
        val name = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"
        return File(home, "bin/$name").takeIf { it.exists() }?.path ?: "cargo"
    }
}

val native = rootProject.layout.projectDirectory.dir("native")
val rust = tasks.register<CargoNdkBuild>("buildRust") {
    workspace = native
    sources.from(fileTree(native) { exclude("target/**", "**/*.md") })
    crate = "aiostreams-native-android"
    // Only the ABIs this build packs (-Pnuvio.android.abis), so CI builds arm64-v8a alone.
    abis = androidAbis
    minSdk = libs.versions.android.minSdk.get().toInt()
    ndkVersion = androidComponents.sdkComponents.ndkDirectory.map { it.asFile.name }
    ndk = androidComponents.sdkComponents.ndkDirectory
    jniLibs = layout.buildDirectory.dir("cargo/jniLibs")
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.eibyx.nuviokai.debug")
    }
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            extractMpvlibCxxShared,
            ExtractAarNativeLibrary::outputDirectory,
        )
    }
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(rust, CargoNdkBuild::jniLibs)
    }
}

sentry {
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryMappingUploadEnabled)
    uploadNativeSymbols.set(false)
    autoUploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(false)
    autoUploadSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    sentryAuthToken?.let(authToken::set)
    sentryOrg?.let(org::set)
    sentryProject?.let(projectName::set)
    ignoredBuildTypes.set(setOf("debug"))
    autoInstallation {
        enabled.set(false)
    }
    tracingInstrumentation {
        enabled.set(false)
    }
}

dependencies {
    mpvlibAar(variantOf(libs.mpvlib) { artifactType("aar") })
    implementation(project(":composeApp"))
    implementation(libs.androidx.appcompat)
    implementation(libs.compose.runtime)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
    debugImplementation("androidx.compose.ui:ui-test-manifest:${libs.versions.composeMultiplatform.get()}")
}
