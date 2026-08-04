plugins {
    alias(libs.plugins.convention.applicationPlugin)
}

android {
    // PR #1 CW decoder uses the Morse Expert native decoder, which ships only armeabi-v7a.
    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }
}
