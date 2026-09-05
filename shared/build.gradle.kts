plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
}

ktlint {
    // Compose's resource generator writes source into build/generated, which ktlint
    // otherwise picks up as part of the source set it's attached to.
    filter {
        exclude { it.file.path.contains("/generated/") }
    }
}

kotlin {
    jvm()

    sourceSets {
        // The expressive half of Material 3 — MaterialExpressiveTheme, MotionScheme, the emphasized
        // type styles, LoadingIndicator, FloatingToolbar — is still behind an opt-in. It is opted
        // into once here rather than at each call site because the theme is app-wide: every screen
        // reads an emphasized style or a motion spec, so per-file annotations would just be noise.
        all {
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        }

        commonMain.dependencies {
            // api(), against the kuiver rule below and for the reason that rule gives: the tray and
            // the menu bar in :desktopApp name RunRegistry, RunStatus, NodeRun and NodeType, so
            // hiding :core here would only force a second declaration there.
            api(project(":core"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.kuiver)
            implementation(libs.aboutLibraries.composeM3)

            implementation(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.uiTest)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
        commonTest { kotlin.srcDir(rootProject.file("core/src/testFixtures/kotlin")) }
    }
}
