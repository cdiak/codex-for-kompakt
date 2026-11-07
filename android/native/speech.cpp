#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

static std::mutex engine_mutex;
static whisper_context * engine = nullptr;
static std::atomic<int> cancelled_through{0};
static bool aborted(void * id) { return cancelled_through.load() >= *static_cast<int *>(id); }
static void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message);
}

extern "C" JNIEXPORT void JNICALL
Java_org_kompakt_remote_WhisperEngine_nativeCancel(JNIEnv *, jclass, jint id) {
    int old = cancelled_through.load();
    while (old < id && !cancelled_through.compare_exchange_weak(old, id)) {}
}
extern "C" JNIEXPORT void JNICALL
Java_org_kompakt_remote_WhisperEngine_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (engine) whisper_free(engine);
    engine = nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_kompakt_remote_WhisperEngine_nativeTranscribe(JNIEnv * env, jclass, jstring model, jshortArray audio, jint id) {
    std::lock_guard<std::mutex> guard(engine_mutex);
    int job = id;
    if (aborted(&job)) return nullptr;
    if (!engine) {
        // Suppress library diagnostics: neither audio nor transcripts are logged.
        whisper_log_set([](enum ggml_log_level, const char *, void *) {}, nullptr);
        const char * path = env->GetStringUTFChars(model, nullptr);
        auto context_params = whisper_context_default_params();
        context_params.use_gpu = false;
        engine = whisper_init_from_file_with_params(path, context_params);
        env->ReleaseStringUTFChars(model, path);
        if (!engine) { fail(env, "Could not load the local speech model."); return nullptr; }
    }
    if (aborted(&job)) return nullptr;
    const int count = env->GetArrayLength(audio);
    if (count < 1600 || count > 480000) { fail(env, "Record between a moment and 30 seconds."); return nullptr; }
    std::vector<jshort> pcm(count);
    env->GetShortArrayRegion(audio, 0, count, pcm.data());
    std::vector<float> samples(count);
    for (int i = 0; i < count; ++i) samples[i] = pcm[i] / 32768.0f;
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;
    params.language = "en";
    params.no_context = true;
    params.no_timestamps = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_nst = true;
    params.temperature = 0;
    params.temperature_inc = 0;
    params.greedy.best_of = 1;
    params.abort_callback = aborted;
    params.abort_callback_user_data = &job;
    int result = whisper_full(engine, params, samples.data(), count);
    if (aborted(&job)) return nullptr;
    if (result != 0) { fail(env, "Local transcription failed. Try recording again."); return nullptr; }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(engine); ++i) text += whisper_full_get_segment_text(engine, i);
    auto out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}
