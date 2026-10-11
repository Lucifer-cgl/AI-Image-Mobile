#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <string>
#include <thread>
#include <vector>
#include "llama.h"

#define TAG "MobileImagePlanner"

static std::string jstr(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    std::string out(raw ? raw : "");
    if (raw) env->ReleaseStringUTFChars(value, raw);
    return out;
}

static void batch_set(llama_batch_ext* batch, const llama_token* tokens, int count, llama_pos start) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        const int idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = start + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aimangastudio_planner_PlannerEngine_plan(JNIEnv* env, jclass, jstring modelPath,
                                                   jstring systemPrompt, jstring userPrompt,
                                                   jint maxTokens) {
    const std::string modelPathUtf8 = jstr(env, modelPath);
    const std::string system = jstr(env, systemPrompt);
    const std::string user = jstr(env, userPrompt);
    const std::string prompt = "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" +
        user + "<|im_end|>\n<|im_start|>assistant\n";
    std::string output;
    bool jsonStarted = false;
    bool inString = false;
    bool escaped = false;
    int braceDepth = 0;
    int generatedCount = 0;
    const auto startedAt = std::chrono::steady_clock::now();
    llama_model* model = nullptr;
    llama_context* context = nullptr;
    llama_sampler* sampler = nullptr;
    llama_batch_ext* batch = nullptr;

    llama_backend_init();
    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    model = llama_model_load_from_file(modelPathUtf8.c_str(), modelParams);
    if (!model) {
        llama_backend_free();
        return env->NewStringUTF("{\"error\":\"无法加载中文画面规划模型\"}");
    }
    const llama_vocab* vocab = llama_model_get_vocab(model);
    int tokenCount = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (tokenCount <= 0) goto cleanup;
    {
        std::vector<llama_token> tokens(tokenCount);
        if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true) < 0) goto cleanup;
        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = std::max(2048, tokenCount + (int)maxTokens + 32);
        contextParams.n_batch = tokenCount;
        contextParams.n_threads = std::max(2, std::min(8, (int)std::thread::hardware_concurrency() - 1));
        contextParams.n_threads_batch = contextParams.n_threads;
        context = llama_init_from_model(model, contextParams);
        if (!context) goto cleanup;
        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        batch = llama_batch_ext_init(context);
        batch_set(batch, tokens.data(), tokenCount, 0);
        int position = 0;
        int currentCount = tokenCount;
        const int limit = std::max(64, std::min(768, (int)maxTokens));
        for (int generated = 0; generated < limit; ++generated) {
            if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) break;
            position += currentCount;
            llama_token next = llama_sampler_sample(sampler, context, -1);
            if (llama_vocab_is_eog(vocab, next)) break;
            char piece[512];
            int length = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
            if (length > 0) {
                output.append(piece, length);
                for (int i = 0; i < length; ++i) {
                    const char ch = piece[i];
                    if (!jsonStarted) {
                        if (ch == '{') {
                            jsonStarted = true;
                            braceDepth = 1;
                        }
                        continue;
                    }
                    if (escaped) {
                        escaped = false;
                    } else if (ch == '\\' && inString) {
                        escaped = true;
                    } else if (ch == '"') {
                        inString = !inString;
                    } else if (!inString && ch == '{') {
                        ++braceDepth;
                    } else if (!inString && ch == '}') {
                        --braceDepth;
                    }
                }
            }
            generatedCount = generated + 1;
            if (jsonStarted && braceDepth == 0 && !inString) break;
            batch_set(batch, &next, 1, position);
            currentCount = 1;
        }
    }

cleanup:
    {
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - startedAt).count();
        __android_log_print(ANDROID_LOG_INFO, TAG, "planner finished tokens=%d json=%d elapsed_ms=%lld",
            generatedCount, jsonStarted && braceDepth == 0, (long long)elapsed);
    }
    if (batch) llama_batch_ext_free(batch);
    if (sampler) llama_sampler_free(sampler);
    if (context) llama_free(context);
    if (model) llama_model_free(model);
    llama_backend_free();
    return env->NewStringUTF(output.empty() ? "{\"error\":\"画面规划没有返回内容\"}" : output.c_str());
}
