import com.mikepenz.aboutlibraries.plugin.StrictMode
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
    // version pinned in the root build
    id("org.jetbrains.compose.hot-reload")
    alias(libs.plugins.aboutLibraries)
}

ktlint {
    // skip compose generated source
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

val stageAppResources =
    tasks.register<Sync>("stageAppResources") {
        description = "Stages the license into the app resources directory jpackage copies in before signing."
        from(rootProject.file("LICENSE"))
        into(layout.buildDirectory.dir("appResources/common"))
    }

val buildNotifier =
    tasks.register<Exec>("buildNotifier") {
        description = "Compiles the JNI notification library into the Apple Silicon app resources jpackage signs."
        onlyIf { System.getProperty("os.name").startsWith("Mac") }
        val source = rootProject.file("core/src/commonMain/objc/notify.m")
        val output = layout.buildDirectory.file("appResources/macos-arm64/libzopf-notify.dylib")
        val javaHome = providers.systemProperty("java.home")
        inputs.file(source)
        outputs.file(output)
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "-dynamiclib",
                    "-fobjc-arc",
                    "-O2",
                    "-arch",
                    "arm64",
                    "-mmacosx-version-min=11.0",
                    "-I${javaHome.get()}/include",
                    "-I${javaHome.get()}/include/darwin",
                    "-framework",
                    "Foundation",
                    "-framework",
                    "UserNotifications",
                    "-o",
                    output.get().asFile.absolutePath,
                    source.absolutePath,
                )
            },
        )
        executable = "clang"
    }

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageAppResources, buildNotifier) }

val appName = "zopf"

compose.desktop {
    application {
        mainClass = "com.dk.zopf.MainKt"

        nativeDistributions {
            // from suggestRuntimeModules
            modules("java.instrument", "jdk.httpserver", "jdk.unsupported")

            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))

            targetFormats(TargetFormat.Dmg)
            // what Finder and the menu bar show
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

// LaunchServices applies the Info.plist and the .icns
tasks.register<Exec>("runMacApp") {
    group = "compose desktop"
    description = "Builds the .app bundle and opens it detached from Gradle (macOS only)."
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").startsWith("Mac") }

    val appBundle =
        layout.buildDirectory
            .file("compose/binaries/main/app/$appName.app")
            .map { it.asFile.absolutePath }

    // -n forces a fresh instance
    argumentProviders.add(CommandLineArgumentProvider { listOf("-n", appBundle.get()) })
    executable = "open"

    doFirst {
        // evicts LaunchServices' cached icon
        File(appBundle.get()).setLastModified(System.currentTimeMillis())
    }
}
