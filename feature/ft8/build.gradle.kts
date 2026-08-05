plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.bg7yoz.ft8cn"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        dataBinding = true
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "apkBuildTime", "\"2026-08-05\"")
        buildConfigField("String", "APPLICATION_ID", "\"com.bg7yoz.ft8cn\"")
        buildConfigField("String", "VERSION_NAME", "\"0.93\"")
    }
    androidComponents {
        onVariants(selector().all()) { variant ->
            (variant as? com.android.build.api.variant.CanProduceConsumerProguardFiles)
                ?.consumerProguardFiles?.add(
                    project.layout.projectDirectory.file("proguard-rules.pro")
                )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-livedata:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.5.1")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.navigation:navigation-fragment:2.5.3")
    implementation("androidx.navigation:navigation-ui:2.5.3")
    implementation("com.google.android.gms:play-services-maps:18.1.0")
    implementation("com.google.guava:guava:31.1-jre")
    implementation(files("libs/MPAndroidChartv_3.1.0.jar"))
    implementation(files("libs/commons-net-3.6.jar"))
    implementation(files("libs/nanohttpd-2.2.0.jar"))
    implementation(files("libs/osmdroid-android-6.1.14.aar"))
}
