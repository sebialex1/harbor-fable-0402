// native_bridge.cpp — JNI bridge for adrenotools driver loading
// Implements the native methods declared in AdrenoToolsBridge.kt and NativeLoader.kt

#include <jni.h>
#include <android/log.h>
#include <string>
#include <zip.h>
#include <sys/system_properties.h>
#include <dlfcn.h>

#define LOG_TAG "FableNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string getSystemProperty(const char* key) {
    char value[92] = {0};
    __system_property_get(key, value);
    return std::string(value);
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_validateDriverZip(
    JNIEnv* env, jobject thiz, jstring jZipPath) {

    const char* zipPath = env->GetStringUTFChars(jZipPath, nullptr);

    // Open the zip
    int err = 0;
    zip_t* archive = zip_open(zipPath, ZIP_RDONLY, &err);
    if (!archive) {
        env->ReleaseStringUTFChars(jZipPath, zipPath);
        return env->NewStringUTF("Failed to open zip file");
    }

    // Check for meta.json
    zip_int64_t metaIdx = zip_name_locate(archive, "meta.json", 0);
    bool hasMeta = (metaIdx >= 0);

    // Check for a .so library (vulkan.radeon.so, libvulkan.so, etc.)
    bool hasSo = false;
    std::string soName;
    zip_int64_t numEntries = zip_get_num_entries(archive, 0);
    for (zip_int64_t i = 0; i < numEntries; i++) {
        const char* name = zip_get_name(archive, i, 0);
        if (name) {
            std::string entryName(name);
            if (entryName.length() > 3 &&
                entryName.substr(entryName.length() - 3) == ".so") {
                hasSo = true;
                soName = entryName;
                break;
            }
        }
    }

    zip_close(archive);
    env->ReleaseStringUTFChars(jZipPath, zipPath);

    if (!hasMeta) {
        return env->NewStringUTF("Missing meta.json in driver package");
    }
    if (!hasSo) {
        return env->NewStringUTF("No .so library found in driver package");
    }

    LOGI("Driver zip validated: %s", soName.c_str());
    return nullptr; // null = success
}

JNIEXPORT jstring JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_installDriver(
    JNIEnv* env, jobject thiz, jstring jZipPath, jstring jDestDir) {

    const char* zipPath = env->GetStringUTFChars(jZipPath, nullptr);
    const char* destDir = env->GetStringUTFChars(jDestDir, nullptr);

    int err = 0;
    zip_t* archive = zip_open(zipPath, ZIP_RDONLY, &err);
    if (!archive) {
        env->ReleaseStringUTFChars(jZipPath, zipPath);
        env->ReleaseStringUTFChars(jDestDir, destDir);
        return nullptr;
    }

    // Extract all files to destDir
    zip_int64_t numEntries = zip_get_num_entries(archive, 0);
    std::string installedPath;

    for (zip_int64_t i = 0; i < numEntries; i++) {
        const char* name = zip_get_name(archive, i, 0);
        if (!name) continue;

        std::string entryName(name);
        std::string fullPath = std::string(destDir) + "/" + entryName;

        // Skip directories
        if (entryName.back() == '/') continue;

        zip_file_t* file = zip_fopen_index(archive, i, 0);
        if (!file) continue;

        FILE* out = fopen(fullPath.c_str(), "wb");
        if (!out) {
            zip_fclose(file);
            continue;
        }

        char buf[8192];
        zip_int64_t bytesRead;
        while ((bytesRead = zip_fread(file, buf, sizeof(buf))) > 0) {
            fwrite(buf, 1, bytesRead, out);
        }

        fclose(out);
        zip_fclose(file);

        if (entryName.length() > 3 &&
            entryName.substr(entryName.length() - 3) == ".so") {
            installedPath = fullPath;
        }
    }

    zip_close(archive);
    env->ReleaseStringUTFChars(jZipPath, zipPath);
    env->ReleaseStringUTFChars(jDestDir, destDir);

    if (installedPath.empty()) {
        return nullptr;
    }

    LOGI("Driver installed to: %s", installedPath.c_str());
    return env->NewStringUTF(installedPath.c_str());
}

JNIEXPORT jlong JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_loadDriver(
    JNIEnv* env, jobject thiz, jstring jLibraryPath) {

    const char* libPath = env->GetStringUTFChars(jLibraryPath, nullptr);

    // Load the driver library
    void* handle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        LOGE("Failed to load driver: %s — %s", libPath, dlerror());
        env->ReleaseStringUTFChars(jLibraryPath, libPath);
        return 0;
    }

    LOGI("Driver loaded: %s", libPath);
    env->ReleaseStringUTFChars(jLibraryPath, libPath);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jboolean JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_unloadDriver(
    JNIEnv* env, jobject thiz, jlong handle) {

    void* ptr = reinterpret_cast<void*>(handle);
    if (ptr) {
        dlclose(ptr);
        LOGI("Driver unloaded");
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_getVkGetInstanceProcAddr(
    JNIEnv* env, jobject thiz, jlong handle) {

    void* ptr = reinterpret_cast<void*>(handle);
    if (!ptr) return 0;

    void* func = dlsym(ptr, "vk_icdGetInstanceProcAddr");
    if (!func) {
        func = dlsym(ptr, "vkGetInstanceProcAddr");
    }

    return reinterpret_cast<jlong>(func);
}

JNIEXPORT jboolean JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_isAdrenoToolsSupported(
    JNIEnv* env, jobject thiz) {

    // Check if we're on ARM64
    std::string abi = getSystemProperty("ro.product.cpu.abi");
    bool isArm64 = (abi == "arm64-v8a");

    // Check Android version >= 10 (API 29 for adrenotools linker namespace)
    std::string sdkStr = getSystemProperty("ro.build.version.sdk");
    int sdk = atoi(sdkStr.c_str());
    bool sdkOk = sdk >= 29;

    LOGI("Adrenotools support check: abi=%s sdk=%d -> %s",
         abi.c_str(), sdk, (isArm64 && sdkOk) ? "true" : "false");

    return isArm64 && sdkOk ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_getGpuInfo(
    JNIEnv* env, jobject thiz) {

    std::string gpu = getSystemProperty("ro.hardware.egl");
    std::string vendor = getSystemProperty("ro.hardware.chipname");
    std::string model = getSystemProperty("ro.product.model");

    std::string info = "GPU: " + gpu + " | Vendor: " + vendor + " | Device: " + model;
    return env->NewStringUTF(info.c_str());
}

// === NativeLoader stubs ===

JNIEXPORT jint JNICALL
Java_io_harbor_fable_nativebridge_NativeLoader_launchWineContainer(
    JNIEnv* env, jobject thiz, jstring jContainerPath, jstring jExePath,
    jobjectArray jEnvVars, jstring jDriverPath) {

    // TODO: Implement Wine container launch
    // This will fork/exec a wine process with the appropriate
    // environment variables and driver preloaded
    LOGI("Wine container launch requested (not yet implemented)");
    return -1;
}

JNIEXPORT jboolean JNICALL
Java_io_harbor_fable_nativebridge_NativeLoader_isWineAvailable(
    JNIEnv* env, jobject thiz, jstring jWinePath) {

    const char* winePath = env->GetStringUTFChars(jWinePath, nullptr);
    FILE* f = fopen(winePath, "r");
    bool exists = (f != nullptr);
    if (f) fclose(f);
    env->ReleaseStringUTFChars(jWinePath, winePath);
    return exists ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
