plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.radar"
}

dependencies {
    implementation(project(":feature:mutual"))
    // CW 解码面板: 复用 feature:cw 的 fldigi 解码引擎(纯 Compose 面板)
    implementation(project(":feature:cw"))
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}