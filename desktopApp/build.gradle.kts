import com.mikepenz.aboutlibraries.plugin.StrictMode
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
    // Provides the `hotRun` and `hotMcpServer` tasks the README documents. No version
    // here: the root build declares it, which is what pins the version for the whole
    // build, and repeating it in a subproject fails the resolution.
    id("org.jetbrains.compose.hot-reload")
    alias(libs.plugins.aboutLibraries)
}

ktlint {
    // Compose's resource generator writes source into build/generated, which ktlint
    // otherwise picks up as part of the source set it's attached to.
    filter {
        exclude { it.file.path.contains("/generated/") }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

aboutLibraries {
    collect {
        includePlatform = false
    }
    license {
        strictMode = StrictMode.FAIL
        allowedLicenses.addAll("Apache-2.0", "BSD-3-Clause", "MIT")
    }
    export {
        outputFile = rootProject.file("shared/src/commonMain/composeResources/files/aboutlibraries.json")
        prettyPrint = true
    }
}

// jpackage copies appResourcesRootDir/common into the bundle before it is signed, which is the
// only safe moment: anything added to Contents afterwards breaks the code signature. LICENSE is
// committed, so this stays a plain copy and `run` keeps its configuration cache entry.
val stageAppResources by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"))
    into(layout.buildDirectory.dir("appResources/common"))
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageAppResources) }

val appName = "zopf"

compose.desktop {
    application {
        mainClass = "com.dk.zopf.MainKt"

        nativeDistributions {
            // jlink cuts the bundled runtime down to a default set that this app doesn't fit in:
            // PermissionBridge's loopback server is com.sun.net.httpserver, which lives in
            // jdk.httpserver, and without it every claude node fails in the .app while working
            // perfectly under `run`. These three are what `./gradlew :desktopApp:suggestRuntimeModules`
            // reports — re-run it after adding a dependency or touching the runtime layer.
            modules("java.instrument", "jdk.httpserver", "jdk.unsupported")

            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))

            targetFormats(TargetFormat.Dmg)
            // Becomes the bundle name macOS shows in Finder and the menu bar, so it
            // has to stay human-readable; the reverse-DNS id goes on bundleID below.
            packageName = appName
            packageVersion = (findProperty("packageVersion") as String?) ?: "1.0.0"

            macOS {
                bundleID = "com.dk.zopf"
                iconFile.set(project.file("icons/icon.icns"))
            }
            windows { iconFile.set(project.file("icons/icon.ico")) }
            linux { iconFile.set(project.file("icons/icon.png")) }
        }
    }
}

// Closest thing to how a user would actually start the app: LaunchServices owns
// the process, so the bundle's Info.plist and .icns apply, and it keeps running
// after the build finishes instead of hanging off the Gradle daemon.
tasks.register<Exec>("runMacApp") {
    group = "compose desktop"
    description = "Builds the .app bundle and opens it detached from Gradle (macOS only)."
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").startsWith("Mac") }

    val appBundle =
        layout.buildDirectory
            .file("compose/binaries/main/app/$appName.app")
            .map { it.asFile.absolutePath }

    // -n forces a fresh instance instead of focusing one that is already running.
    argumentProviders.add(CommandLineArgumentProvider { listOf("-n", appBundle.get()) })
    executable = "open"

    doFirst {
        // LaunchServices caches icons per bundle; bumping the mtime makes it
        // re-read the .icns after the artwork changes.
        File(appBundle.get()).setLastModified(System.currentTimeMillis())
    }
}
