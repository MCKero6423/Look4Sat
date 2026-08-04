plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.cw"
    compileOptions {
        encoding = "UTF-8"
    }
    // 照搬 Morse Expert 1.15: native 解码器仅 armeabi-v7a
    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }
}

dependencies {
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}

// 照搬的 Java 源码含中文注释, 强制 UTF-8 编译(compileOptions 在部分 AGP 版本不生效)
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
