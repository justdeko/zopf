import com.mikepenz.aboutlibraries.plugin.StrictMode

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
    application
    alias(libs.plugins.aboutLibraries)
}

// cli has fewer deps than the app
aboutLibraries {
    collect {
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

// names the release tarball zopf-cli-<version>.tar.gz
version = (findProperty("packageVersion") as String?) ?: "1.0.0"

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass = "com.dk.zopf.cli.MainKt"
    // name that shows up in launcher via installDist and the tarball dir
    applicationName = "zopf"
}

distributions {
    main {
        distributionBaseName = "zopf-cli"
        // include license in cli tarball
        contents {
            from(rootProject.file("LICENSE"))
        }
    }
}

// homebrew needs a gzipped tarball
tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

tasks.startScripts {
    doLast { windowsScript.delete() }
}
