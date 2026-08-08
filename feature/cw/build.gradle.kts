import com.android.build.api.variant.CanProduceConsumerProguardFiles

plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.cw"
    compileOptions {
        encoding = "UTF-8"
    }
}

// R8 consumer rules (kept for compatibility; the fldigi port has no native code)
androidComponents {
    onVariants(selector().all()) { variant ->
        (variant as? CanProduceConsumerProguardFiles)?.consumerProguardFiles?.add(
            project.layout.projectDirectory.file("proguard-rules.pro")
        )
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
}
