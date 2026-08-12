import com.mikepenz.aboutlibraries.plugin.StrictMode

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
    application
    alias(libs.plugins.aboutLibraries)
}

// The CLI ships a much smaller dependency set than the app, so it gets its own list rather than
// the app's superset. The JSON lands in resources and so travels inside the jar.
aboutLibraries {
    collect {
        fetchRemoteLicense = true
        includePlatform = false
    }
    license {
        strictMode = StrictMode.FAIL
        allowedLicenses.addAll("Apache-2.0", "BSD-3-Clause", "MIT")
    }
    export {
        outputFile = file("src/main/resources/aboutlibraries.json")
        prettyPrint = true
    }
}

// Names the tarball the release publishes: zopf-cli-<version>.tar.gz.
version = (findProperty("packageVersion") as String?) ?: "1.0.0"

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass = "com.dk.zopf.cli.MainKt"
    // The launcher installDist writes, and the directory the tarball unpacks into.
    applicationName = "zopf"
}

distributions {
    main {
        distributionBaseName = "zopf-cli"
        // The obligations attach to the tarball, not to the repo. The library list and every
        // license text ride along inside the jar; zopf's own license lives in no jar.
        contents {
            from(rootProject.file("LICENSE"))
        }
    }
}

// Homebrew wants a gzipped tarball, and the plain .tar the plugin defaults to isn't one.
tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

// zopf is macOS-only — AppPaths resolves ~/Library/Application Support and the runners spawn
// zsh — so shipping the plugin's Windows launcher only invites a bug report.
tasks.startScripts {
    doLast { windowsScript.delete() }
}
