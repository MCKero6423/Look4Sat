plugins {
    alias(libs.plugins.convention.coreDataPlugin)
}

android {
    namespace = "com.rtbishop.look4sat.core.data"
}

dependencies {
    // DeepCW 神经网络 CW 解码推理。ONNX 推理属 Android 平台依赖, 放此处而非
    // core:domain —— 后者须保持纯 Kotlin/JVM 以留 KMP 迁移余地 (见 AGENTS.md)。
    implementation(libs.other.onnxruntime)
}
