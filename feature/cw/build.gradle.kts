import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.CanProduceConsumerProguardFiles

plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.cw"
    ndkVersion = "25.2.9519653"
    compileOptions {
        encoding = "UTF-8"
    }
    defaultConfig {
        // fldigi CW 解码器: 支持 arm64-v8a + armeabi-v7a 双架构
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// CW 类保持规则(JNI 按类名注册 + 照搬混淆类保逻辑), 由 app 的 R8 消费
// AGP 9: consumer 规则走 variant 级 CanProduceConsumerProguardFiles
androidComponents {
    onVariants(selector().all()) { variant ->
        (variant as? CanProduceConsumerProguardFiles)?.consumerProguardFiles?.add(
            project.layout.projectDirectory.file("proguard-rules.pro")
        )
    }
}