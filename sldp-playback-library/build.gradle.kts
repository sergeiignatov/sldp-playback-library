plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
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

configure<PublishingExtension> {
    publications.create<MavenPublication>("sldp-playback-library") {
        groupId = "com.github.sergeiignatov"
        artifactId = "sldp-playback-library"
        version = "0.0.1-alpha01"
        pom.packaging = "aar"
        artifact("$buildDir/outputs/aar/sldp-playback-library-release.aar")
    }
    repositories {
        mavenLocal()
    }
}
