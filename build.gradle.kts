plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    // Compose Multiplatform drags in a hot-reload plugin of its own, but only as a
    // `prefer` constraint (1.1.1 as of CMP 1.11.1) — which is old enough to have no
    // `hotMcpServer` task. Naming a version here is what raises it; a `prefer`
    // yields to an explicit request, so the subprojects' version-less `apply` gets
    // this one.
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.aboutLibraries) apply false
}
