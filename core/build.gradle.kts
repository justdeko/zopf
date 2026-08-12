plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktlint)
}

// One version for every surface. Generated rather than checked in so a tagged release and a local
// build can never disagree with the jar they came from; read back through store/BuildInfo.kt.
val zopfVersion = (findProperty("packageVersion") ?: findProperty("zopfVersion") ?: "0.0.0").toString()

// The workflow file format, bumped by hand when a change alters what an existing file means. Not
// the app's version and never overridden by the release tag: the two move independently.
val workflowVersion = (findProperty("workflowVersion") ?: "1").toString()

val writeVersion =
    tasks.register("writeVersion") {
        description = "Writes the build's version and the workflow format version into resources read back by store/BuildInfo.kt."
        // Locals, not the script's properties: the configuration cache can't serialize a reference
        // back into the build script from a task action.
        val version = zopfVersion
        val format = workflowVersion
        // A directory, because this is wired in as a resources srcDir rather than a single file.
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
            // Snapshot state, not the compiler: NodeRun and RunRegistry are observable by a
            // composition when there is one, and ordinary objects when there isn't. It is api()
            // because those objects hand a SnapshotStateList back to whoever reads them.
            api(libs.compose.runtime)

            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotaml)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Fixtures :shared's tests compile too — see ExampleWorkflow.kt for why.
        commonTest { kotlin.srcDir("src/testFixtures/kotlin") }

        commonMain { resources.srcDir(writeVersion) }
    }
}
