#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

// JNI Utility code from original file
struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;
    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) { /* Original implementation */
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;
    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);
    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);
    if (size_to_copy != read_size || size_to_copy != n_read) { LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read); }
    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);
    (*is->env)->DeleteLocalRef(is->env, byte_array);
    is->offset += size_to_copy;
    return size_to_copy;
}
bool inputStreamEof(void * ctx) { /* Original implementation */
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) { /* Original implementation */ }

static size_t asset_read(void *ctx, void *output, size_t read_size) { return AAsset_read((AAsset *) ctx, output, read_size); }
static bool asset_is_eof(void *ctx) { return AAsset_getRemainingLength64((AAsset *) ctx) <= 0; }
static void asset_close(void *ctx) { AAsset_close((AAsset *) ctx); }

// Structure to hold both context and state
struct whisper_jni_context {
    struct whisper_context *ctx;
    struct whisper_state *state;
    int n_threads;
};

// --- MODIFIED JNI FUNCTIONS ---

// Common function to create the jni_context after whisper_context is created
jlong create_jni_context(struct whisper_context * ctx) {
    if (ctx == NULL) {
        LOGW("whisper_context is null, cannot create jni_context");
        return 0;
    }
    struct whisper_jni_context *jni_ctx = (struct whisper_jni_context *) malloc(sizeof(struct whisper_jni_context));
    jni_ctx->ctx = ctx;
    jni_ctx->state = whisper_init_state(jni_ctx->ctx);
    if (jni_ctx->state == NULL) {
        LOGW("Failed to initialize whisper state");
        whisper_free(jni_ctx->ctx);
        free(jni_ctx);
        return 0;
    }
    jni_ctx->n_threads = 4; // Default value
    return (jlong) jni_ctx;
}

JNIEXPORT jlong JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};
    inp_ctx.offset = 0; inp_ctx.env = env; inp_ctx.thiz = thiz; inp_ctx.input_stream = input_stream;
    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");
    loader.context = &inp_ctx; loader.read = inputStreamRead; loader.eof = inputStreamEof; loader.close = inputStreamClose;
    context = whisper_init(&loader);
    return create_jni_context(context);
}

JNIEXPORT jlong JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(mgr, asset_path_chars, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open asset '%s'", asset_path_chars);
        (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
        return 0;
    }
    whisper_model_loader loader = { .context = asset, .read = &asset_read, .eof = &asset_is_eof, .close = &asset_close };
    struct whisper_context *context = whisper_init_with_params(&loader, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return create_jni_context(context);
}

JNIEXPORT jlong JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context *context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return create_jni_context(context);
}

JNIEXPORT void JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong jni_context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_jni_context *jni_ctx = (struct whisper_jni_context *) jni_context_ptr;
    if (jni_ctx) {
        if (jni_ctx->state) whisper_free_state(jni_ctx->state);
        if (jni_ctx->ctx) whisper_free(jni_ctx->ctx);
        free(jni_ctx);
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong jni_context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_jni_context *jni_ctx = (struct whisper_jni_context *) jni_context_ptr;
    if (jni_ctx == NULL || jni_ctx->ctx == NULL) { /* ... error handling ... */ return (*env)->NewStringUTF(env, ""); }

    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "zh";
    params.n_threads = jni_ctx->n_threads;

    if (whisper_full(jni_ctx->ctx, params, audio_data_arr, audio_data_length) != 0) {
        LOGW("Failed to run whisper_full");
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);

    const int n_segments = whisper_full_n_segments(jni_ctx->ctx);
    char result_text[2048] = "";
    for (int i = 0; i < n_segments; ++i) {
        const char *segment = whisper_full_get_segment_text(jni_ctx->ctx, i);
        strncat(result_text, segment, sizeof(result_text) - strlen(result_text) - 1);
    }
    return (*env)->NewStringUTF(env, result_text);
}

JNIEXPORT jstring JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_streamTranscribeData(
        JNIEnv *env, jobject thiz, jlong jni_context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_jni_context *jni_ctx = (struct whisper_jni_context *) jni_context_ptr;
    if (jni_ctx == NULL || jni_ctx->ctx == NULL || jni_ctx->state == NULL) { /* ... error handling ... */ return (*env)->NewStringUTF(env, ""); }

    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    const int n_segments_before = whisper_full_n_segments_from_state(jni_ctx->state);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "zh";
    params.n_threads = jni_ctx->n_threads;
    params.no_context = true;
    params.single_segment = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;

    if (whisper_full_with_state(jni_ctx->ctx, jni_ctx->state, params, audio_data_arr, audio_data_length) != 0) {
        LOGW("Failed to run whisper_full_with_state");
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);

    const int n_segments_after = whisper_full_n_segments_from_state(jni_ctx->state);
    char result_text[2048] = "";
    for (int i = n_segments_before; i < n_segments_after; ++i) {
        const char *segment = whisper_full_get_segment_text_from_state(jni_ctx->state, i);
        strncat(result_text, segment, sizeof(result_text) - strlen(result_text) - 1);
    }
    return (*env)->NewStringUTF(env, result_text);
}

// --- Remaining original utility functions ---
// These need to be adapted to use the new jni_context struct

JNIEXPORT jint JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong jni_context_ptr) {
    UNUSED(env); UNUSED(thiz);
    struct whisper_jni_context *jni_ctx = (struct whisper_jni_context *) jni_context_ptr;
    if (jni_ctx == NULL || jni_ctx->ctx == NULL) return 0;
    // This function is ambiguous in a streaming context. Returning based on state.
    return whisper_full_n_segments_from_state(jni_ctx->state);
}

JNIEXPORT jstring JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong jni_context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_jni_context *jni_ctx = (struct whisper_jni_context *) jni_context_ptr;
    if (jni_ctx == NULL || jni_ctx->ctx == NULL) return (*env)->NewStringUTF(env, "");
    const char *text = whisper_full_get_segment_text_from_state(jni_ctx->state, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jstring JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) { /* Original implementation */
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    return (*env)->NewStringUTF(env, sysinfo);
}

JNIEXPORT jstring JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz, jint n_threads) { /* Original implementation */
     UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    return (*env)->NewStringUTF(env, bench_ggml_memcpy);
}

JNIEXPORT jstring JNICALL
Java_io_github_t112360140_multimodal_1ai_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz, jint n_threads) { /* Original implementation */
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    return (*env)->NewStringUTF(env, bench_ggml_mul_mat);
}
