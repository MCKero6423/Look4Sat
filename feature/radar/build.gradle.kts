plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.radar"
}

dependencies {
    implementation(project(":feature:mutual"))
}