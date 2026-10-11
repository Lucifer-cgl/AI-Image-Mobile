#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>
#include <cstdlib>
#include "stable-diffusion.h"

#define TAG "MobileImageJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::mutex g_mutex;
static sd_ctx_t* g_ctx = nullptr;
static std::string g_model_path;
static std::string g_decoder_path;
static bool g_storage_mapped = true;
static std::string g_last_error;

static void android_sd_log(enum sd_log_level_t level, const char* text, void*) {
    if (!text) return;
    __android_log_print(level >= SD_LOG_WARN ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        TAG, "%s", text);
}

struct ProgressBridge {
    JNIEnv* env;
    jobject listener;
    jmethodID method;
};

static void notify_progress(ProgressBridge* bridge, int percent, const char* stage) {
    if (!bridge || !bridge->listener || !bridge->method) return;
    jstring message = bridge->env->NewStringUTF(stage);
    bridge->env->CallVoidMethod(bridge->listener, bridge->method, percent, message);
    bridge->env->DeleteLocalRef(message);
}

static void sampling_progress(int step, int steps, float, void* data) {
    auto* bridge = static_cast<ProgressBridge*>(data);
    const int safeSteps = std::max(1, steps);
    if (step >= safeSteps) {
        notify_progress(bridge, 86, "采样完成，正在解码图片（此阶段可能需要数分钟）");
    } else {
        const int percent = 28 + std::min(56, (step * 56) / safeSteps);
        notify_progress(bridge, percent, "正在采样生成画面");
    }
}

static std::string jstr(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    std::string out(raw ? raw : "");
    if (raw) env->ReleaseStringUTFChars(value, raw);
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aimangastudio_mobileimage_NativeGenerator_generate(
    JNIEnv* env, jclass, jstring modelPath, jstring decoderPath, jstring prompt, jstring negative,
    jint width, jint height, jint steps, jlong seed, jbyteArray initRgb,
    jint initWidth, jint initHeight, jfloat strength, jboolean storageMapped,
    jobject progressListener) {
    std::lock_guard<std::mutex> guard(g_mutex);
    g_last_error.clear();
    jclass listenerClass = progressListener ? env->GetObjectClass(progressListener) : nullptr;
    jmethodID progressMethod = listenerClass
        ? env->GetMethodID(listenerClass, "onProgress", "(ILjava/lang/String;)V") : nullptr;
    ProgressBridge progressBridge{env, progressListener, progressMethod};
    const std::string model = jstr(env, modelPath);
    const std::string decoder = jstr(env, decoderPath);
    const std::string positive = jstr(env, prompt);
    const std::string negativeText = jstr(env, negative);
    if (!g_ctx || g_model_path != model || g_decoder_path != decoder || g_storage_mapped != (storageMapped == JNI_TRUE)) {
        notify_progress(&progressBridge, 6, "正在加载本地模型");
        if (g_ctx) free_sd_ctx(g_ctx);
        sd_ctx_params_t contextParams;
        sd_ctx_params_init(&contextParams);
        contextParams.model_path = model.c_str();
        contextParams.taesd_path = decoder.empty() ? nullptr : decoder.c_str();
        contextParams.n_threads = std::max(2, std::min(8, (int)sysconf(_SC_NPROCESSORS_ONLN) - 1));
        contextParams.enable_mmap = storageMapped == JNI_TRUE;
        contextParams.flash_attn = true;
        contextParams.diffusion_conv_direct = true;
        contextParams.vae_conv_direct = true;
        contextParams.backend = "diffusion=Vulkan0,te=Vulkan0,vae=Vulkan0";
        contextParams.params_backend = "cpu";
        contextParams.max_vram = "2.5";
        contextParams.conditioning_cache_size = 4;
        sd_set_log_callback(android_sd_log, nullptr);
        LOGI("Creating GPU context with Vulkan runtime and CPU parameter storage");
        g_ctx = new_sd_ctx(&contextParams);
        if (!g_ctx) {
            g_last_error = "模型加载失败：可用内存不足、模型文件损坏或设备不兼容";
            LOGE("Failed to load model");
            if (listenerClass) env->DeleteLocalRef(listenerClass);
            return nullptr;
        }
        g_model_path = model;
        g_decoder_path = decoder;
        g_storage_mapped = storageMapped == JNI_TRUE;
        notify_progress(&progressBridge, 26, "模型加载完成");
    } else {
        notify_progress(&progressBridge, 26, "复用已加载模型");
    }

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    params.prompt = positive.c_str();
    params.negative_prompt = negativeText.c_str();
    params.width = width;
    params.height = height;
    params.sample_params.sample_steps = steps;
    params.sample_params.sample_method = LCM_SAMPLE_METHOD;
    params.sample_params.scheduler = LCM_SCHEDULER;
    params.sample_params.guidance.txt_cfg = 1.0f;
    params.seed = seed;
    params.batch_count = 1;
    params.strength = strength;

    jbyte* initBytes = nullptr;
    if (initRgb && initWidth > 0 && initHeight > 0) {
        initBytes = env->GetByteArrayElements(initRgb, nullptr);
        params.init_image.width = initWidth;
        params.init_image.height = initHeight;
        params.init_image.channel = 3;
        params.init_image.data = reinterpret_cast<uint8_t*>(initBytes);
    }

    sd_image_t* images = nullptr;
    int count = 0;
    sd_cancel_generation(g_ctx, SD_CANCEL_RESET);
    sd_set_progress_callback(sampling_progress, &progressBridge);
    const bool ok = generate_image(g_ctx, &params, &images, &count);
    sd_set_progress_callback(nullptr, nullptr);
    if (initBytes) env->ReleaseByteArrayElements(initRgb, initBytes, JNI_ABORT);
    if (!ok || !images || count < 1 || !images[0].data) {
        g_last_error = "图片生成失败：请关闭其他应用后重试，或改用较小尺寸";
        if (images) free_sd_images(images, count);
        if (listenerClass) env->DeleteLocalRef(listenerClass);
        return nullptr;
    }
    const size_t length = (size_t)images[0].width * images[0].height * images[0].channel;
    jbyteArray result = env->NewByteArray((jsize)length);
    env->SetByteArrayRegion(result, 0, (jsize)length, reinterpret_cast<jbyte*>(images[0].data));
    free_sd_images(images, count);
    notify_progress(&progressBridge, 92, "采样完成");
    if (listenerClass) env->DeleteLocalRef(listenerClass);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aimangastudio_mobileimage_NativeGenerator_lastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aimangastudio_mobileimage_NativeGenerator_cancel(JNIEnv*, jclass) {
    // sd_cancel_generation is specifically intended to interrupt an in-flight
    // generate_image call and must not wait for g_mutex here.
    sd_ctx_t* context = g_ctx;
    if (context) sd_cancel_generation(context, SD_CANCEL_ALL);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aimangastudio_mobileimage_NativeGenerator_release(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_ctx) free_sd_ctx(g_ctx);
    g_ctx = nullptr;
    g_model_path.clear();
    g_decoder_path.clear();
}
