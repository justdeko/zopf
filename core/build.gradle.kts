plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktlint)
}

val zopfVersion = (findProperty("packageVersion") ?: findProperty("zopfVersion") ?: "0.0.0").toString()

val workflowVersion = (findProperty("workflowVersion") ?: "1").toString()

val writeVersion =
    tasks.register("writeVersion") {
        description = "Writes the build's version and the workflow format version into resources read back by store/BuildInfo.kt."
        // locals: config cache can't serialize script props into task actions
        val version = zopfVersion
        val format = workflowVersion
        val output = layout.buildDirectory.dir("generated/zopf")
        inputs.property("version", version)
        inputs.property("workflowVersion", format)
        outputs.dir(output)
        doLast {
            output.get().asFile.apply {
                mkdirs()
                resolve("zopf-version.txt").writeText(version)
                resolve("zopf-workflow-version.txt").writeText(format)
            }
        }
    }

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)

            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotaml)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        commonTest { kotlin.srcDir("src/testFixtures/kotlin") }

        commonMain { resources.srcDir(writeVersion) }
    }
}

// non-jvm dirs read by unit tests so tests don't go stale
tasks.named<Test>("jvmTest") {
    inputs
        .dir(rootProject.layout.projectDirectory.dir(".zopf"))
        .withPropertyName("dogfoodWorkspace")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .dir(rootProject.layout.projectDirectory.dir("plugins/zopf/skills"))
        .withPropertyName("skillDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
