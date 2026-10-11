plugins { id("com.android.application") }

val leanDeviceTest = providers.gradleProperty("leanDeviceTest").orNull == "true"

android {
    namespace = "com.aimangastudio.mobileimage"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.aimangastudio.mobileimage"
        // Vulkan 1.1 core entry points used by the current ggml backend are
        // exported by Android's loader from API 28 onward.
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1-alpha"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
                arguments += listOf("-DANDROID_GPU_BUILD_REV=3")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources {
        noCompress += listOf("gguf")
        // Iterative USB builds reuse the model already copied into app-private
        // storage, avoiding a 1.7 GB transfer for every native-code change.
        ignoreAssetsPattern = "*.gguf:*.safetensors:*.onnx:*.mnn:*.pte"
    }
    packaging { jniLibs.useLegacyPackaging = true }
    sourceSets.named("main") {
        assets.srcDir(rootProject.file("model-packs"))
    }
}

dependencies {
    implementation(project(":planner-runtime"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.14.0")
}
