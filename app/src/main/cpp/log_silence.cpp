#include <dlfcn.h>
#include <jni.h>
#include <cstdint>

extern "C" JNIEXPORT void JNICALL
Java_com_gdreducacional_totemapp_NativeLogSilencer_nativeSetMinPriority(
        JNIEnv *,
        jobject,
        jint priority
) {
    using SetMinPriority = int32_t (*)(int32_t);
    auto *fn = reinterpret_cast<SetMinPriority>(
            dlsym(RTLD_DEFAULT, "__android_log_set_minimum_priority"));
    if (fn == nullptr) {
        void *liblog = dlopen("liblog.so", RTLD_NOW);
        if (liblog != nullptr) {
            fn = reinterpret_cast<SetMinPriority>(
                    dlsym(liblog, "__android_log_set_minimum_priority"));
        }
    }
    if (fn != nullptr) {
        fn(static_cast<int32_t>(priority));
    }
}
