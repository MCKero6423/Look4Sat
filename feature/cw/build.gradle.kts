import com.android.build.api.variant.CanProduceConsumerProguardFiles

plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.cw"
}

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
