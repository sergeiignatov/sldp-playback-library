plugins {
    id("maven-publish")
    alias(libs.plugins.android.library)
}

java.toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
}

android {
    namespace = "com.softvelum.sldp"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.androidx.annotations)
}
