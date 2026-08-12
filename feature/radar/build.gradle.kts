plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.radar"
}

dependencies {
    implementation(project(":feature:mutual"))
    // CW 内嵌面板走 ICwDecoder 接口 + ViewModel state, 实现由 MainContainer 注入,
    // 无需依赖 feature:cw 或 constraintlayout (旧 Morse Expert 布局已删除)
}
