plugins {
    id("com.android.application")
}

android {
    namespace = "com.app.nosatmosphereeffect"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.saad_khan_rind.atmosphere_effect"
        versionName = "5.8.3"
        versionCode = 200583
    }

    flavorDimensions += "apiLevel"

    productFlavors {

        create("v36") {
            dimension = "apiLevel"
            minSdk = 36
            targetSdk = 37
            versionCode = 200583
        }

        create("v33") {
            dimension = "apiLevel"
            minSdk = 33
            targetSdk = 37
            versionCode = 100583
        }

    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}