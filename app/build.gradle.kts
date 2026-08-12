import java.util.Properties

plugins {
    alias(libs.plugins.convention.applicationPlugin)
}

// Load signing config from keystore.properties (gitignored, never commit credentials)
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

android {
    androidResources {
        // 显式保留全部语言(防 shrinkResources 丢弃 in/id 印尼语配置); AGP 9 用 localeFilters
        localeFilters += listOf(
            "en", "zh", "tr", "in", "id", "es", "ru", "si", "uk"
        )
        // DeepCW 模型必须以未压缩形式打包: ONNX Runtime 通过 mmap 直接读取
        // assets, 压缩后无法映射会导致 createSession 失败。noCompress 只在
        // 打包 APK 的 app 模块生效, 在 feature 库模块声明无效。
        noCompress += "onnx"
    }
    signingConfigs {
        if (keystoreProperties["storeFile"] != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
