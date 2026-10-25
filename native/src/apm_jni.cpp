#include "minemic_apm.h"
#include <jni.h>
#include <array>
#include <cstdint>

namespace { void* ptr(jlong value) { return reinterpret_cast<void*>(static_cast<intptr_t>(value)); } }
extern "C" JNIEXPORT jlong JNICALL Java_com_aiaixyz_minemic_audio_NativeApm_create(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(mm_apm_create()));
}
extern "C" JNIEXPORT void JNICALL Java_com_aiaixyz_minemic_audio_NativeApm_destroy(JNIEnv*, jobject, jlong h) { mm_apm_destroy(ptr(h)); }
extern "C" JNIEXPORT void JNICALL Java_com_aiaixyz_minemic_audio_NativeApm_resetEcho(JNIEnv*, jobject, jlong h) { mm_apm_reset_echo(ptr(h)); }
extern "C" JNIEXPORT void JNICALL Java_com_aiaixyz_minemic_audio_NativeApm_setDeskNoise(JNIEnv*, jobject, jlong h, jboolean enabled) { mm_apm_set_desk_noise(ptr(h), enabled); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_aiaixyz_minemic_audio_NativeApm_bufferReference(JNIEnv* env, jobject, jlong h, jbyteArray pcm, jboolean valid) {
    if (!h || !pcm || env->GetArrayLength(pcm) != 1920) return false;
    std::array<uint8_t, 1920> input{};
    env->GetByteArrayRegion(pcm, 0, input.size(), reinterpret_cast<jbyte*>(input.data()));
    return !env->ExceptionCheck() && mm_apm_buffer_reference(ptr(h), input.data(), valid) == 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_aiaixyz_minemic_audio_NativeApm_readReference(JNIEnv* env, jobject, jlong h, jdouble ratio) {
    if (!h) return nullptr;
    std::array<uint8_t, 1921> output{};
    const auto status = mm_apm_read_reference(ptr(h), ratio, output.data());
    if (status != 0 && status != 2) return nullptr;
    output[1920] = status == 0 ? 1 : 0;
    auto result = env->NewByteArray(output.size());
    if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte*>(output.data()));
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_aiaixyz_minemic_audio_NativeApm_render(JNIEnv* env, jobject, jlong h, jbyteArray pcm) {
    if (!h || !pcm || env->GetArrayLength(pcm) != 1920) return false;
    std::array<uint8_t, 1920> input{};
    env->GetByteArrayRegion(pcm, 0, input.size(), reinterpret_cast<jbyte*>(input.data()));
    if (env->ExceptionCheck()) return false;
    return mm_apm_render(ptr(h), input.data()) == 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_aiaixyz_minemic_audio_NativeApm_process(JNIEnv* env, jobject, jlong h, jbyteArray pcm, jboolean ns, jboolean aec) {
    if (!h || !pcm || env->GetArrayLength(pcm) != 960) return nullptr;
    std::array<uint8_t, 960> input{}, output{};
    env->GetByteArrayRegion(pcm, 0, input.size(), reinterpret_cast<jbyte*>(input.data()));
    if (env->ExceptionCheck() || mm_apm_capture(ptr(h), input.data(), output.data(), ns, aec) != 0) return nullptr;
    auto result = env->NewByteArray(output.size());
    if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte*>(output.data()));
    return result;
}
