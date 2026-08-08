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
    // CW 解码已迁移为纯 Kotlin fldigi 引擎, 不再有 native 库;
    // 取消 armeabi-v7a 强制, 按设备 ABI 打包(含 x86_64 模拟器)
    defaultConfig {
    }
    androidResources {
        // 显式保留全部语言(防 shrinkResources 丢弃 in/id 印尼语配置); AGP 9 用 localeFilters
        localeFilters += listOf(
            "en", "zh", "tr", "in", "id", "es", "ru", "si", "uk"
        )
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
