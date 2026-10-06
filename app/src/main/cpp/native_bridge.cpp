// SPDX-License-Identifier: MIT
// JNI bridge for adrenotools driver loading and Wine container launch.
// Method names match AdrenoToolsBridge.kt and NativeLoader.kt.

#include <jni.h>

#include "common/fable_log.h"
#include "meta/driver_meta.h"
#include "vulkan/vulkan_probe.h"
#include "wine/wine_launcher.h"

#include <adrenotools/driver.h>

#include <cstdlib>
#include <exception>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

namespace {

class JString {
public:
    JString(JNIEnv* env, jstring value) : env_(env), value_(value), chars_(nullptr) {
        if (env && value) chars_ = env->GetStringUTFChars(value, nullptr);
    }
    ~JString() {
        if (chars_) env_->ReleaseStringUTFChars(value_, chars_);
    }
    JString(const JString&) = delete;
    JString& operator=(const JString&) = delete;
    const char* get() const { return chars_; }
    bool ok() const { return chars_ != nullptr; }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_;
};

std::string system_property(const char* key) {
#ifdef __ANDROID__
    char value[92] = {0};
    __system_property_get(key, value);
    return std::string(value);
#else
    (void)key;
    return {};
#endif
}

std::vector<std::string> string_array(JNIEnv* env, jobjectArray array, std::string* error) {
    std::vector<std::string> out;
    if (!array) return out;
    const jsize n = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (!item) {
            if (error) *error = "String array contains a null entry";
            return {};
        }
        JString text(env, item);
        env->DeleteLocalRef(item);
        if (!text.ok()) {
            if (error) *error = "Failed to read string array entry";
            return {};
        }
        out.emplace_back(text.get());
    }
    return out;
}

}  // namespace

extern "C" {

// Load-time breadcrumb only. Deliberately installs NO signal handlers: ART owns SIGSEGV (implicit
// null checks, stack overflow checks) and replacing it would turn ordinary Java exceptions into
// native crashes. Native crashes are diagnosed from the launch-log breadcrumbs and tombstones.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (!vm || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        FABLE_LOGE("JNI_OnLoad: GetEnv(JNI_VERSION_1_6) failed");
        return JNI_ERR;
    }
    FABLE_LOGI("libfable_native loaded (sdk=%s, abi=%s, device=%s)",
               system_property("ro.build.version.sdk").c_str(),
               system_property("ro.product.cpu.abi").c_str(),
               system_property("ro.product.model").c_str());
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_validateDriverZip(
    JNIEnv* env, jobject /*thiz*/, jstring jZipPath) {
    if (!jZipPath) return env->NewStringUTF("Failed to open zip file");
    JString zip_path(env, jZipPath);
    if (!zip_path.ok()) return env->NewStringUTF("Failed to open zip file");
    try {
        const std::string error = fable::validate_driver_zip(zip_path.get());
        if (error.empty()) return nullptr;
        return env->NewStringUTF(error.c_str());
    } catch (const std::exception& ex) {
        return env->NewStringUTF(ex.what());
    }
}

JNIEXPORT jstring JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_installDriver(
    JNIEnv* env, jobject /*thiz*/, jstring jZipPath, jstring jDestDir) {
    if (!jZipPath || !jDestDir) return nullptr;
    JString zip_path(env, jZipPath);
    JString dest_dir(env, jDestDir);
    if (!zip_path.ok() || !dest_dir.ok()) return nullptr;
    try {
        std::string error;
        const std::string installed = fable::install_driver_zip(zip_path.get(), dest_dir.get(), &error);
        if (installed.empty()) return nullptr;
        return env->NewStringUTF(installed.c_str());
    } catch (const std::exception& ex) {
        FABLE_LOGE("installDriver exception: %s", ex.what());
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_loadDriver(
    JNIEnv* env, jobject /*thiz*/, jstring jLibraryPath) {
    if (!jLibraryPath) return 0;
    JString library(env, jLibraryPath);
    if (!library.ok()) return 0;
    try {
        void* handle = adrenotools_load_driver(library.get(), nullptr);
        if (!handle) {
            FABLE_LOGE("loadDriver failed: %s", adrenotools_last_error());
            return 0;
        }
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception& ex) {
        FABLE_LOGE("loadDriver exception: %s", ex.what());
        return 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_unloadDriver(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    return adrenotools_close(reinterpret_cast<void*>(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_getVkGetInstanceProcAddr(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return 0;
    void* fn = adrenotools_get_instance_proc_addr(reinterpret_cast<void*>(handle));
    if (!fn) fn = reinterpret_cast<void*>(&adrenotools_vkGetInstanceProcAddr);
    return reinterpret_cast<jlong>(fn);
}

JNIEXPORT jboolean JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_isAdrenoToolsSupported(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // Namespace bypass is usable from Android 9; memfd unique-load wants API 29.
    // The bridge reports support on arm64 + API 29+, matching the previous check.
    const std::string abi = system_property("ro.product.cpu.abi");
    const int sdk = std::atoi(system_property("ro.build.version.sdk").c_str());
    const bool arm64 = abi == "arm64-v8a";
    const bool sdk_ok = sdk >= 29;
    FABLE_LOGI("Adrenotools support check: abi=%s sdk=%d -> %s",
               abi.c_str(), sdk, (arm64 && sdk_ok) ? "true" : "false");
    return (arm64 && sdk_ok) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_getGpuInfo(
    JNIEnv* env, jobject /*thiz*/) {
    const std::string egl = system_property("ro.hardware.egl");
    const std::string vulkan = system_property("ro.hardware.vulkan");
    const std::string chip = system_property("ro.hardware.chipname");
    const std::string hardware = system_property("ro.hardware");
    const std::string model = system_property("ro.product.model");
    const std::string abi = system_property("ro.product.cpu.abi");
    const std::string sdk = system_property("ro.build.version.sdk");
    std::string gpu = !vulkan.empty() ? vulkan : egl;
    std::string vendor = !chip.empty() ? chip : hardware;
    std::string info = "GPU: " + gpu + " | Vendor: " + vendor + " | Device: " + model +
                       " | ABI: " + abi + " | SDK: " + sdk;
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_harbor_fable_nativebridge_AdrenoToolsBridge_probeVulkanExtensions(
    JNIEnv* env, jobject /*thiz*/, jstring jLibraryPath) {
    std::string library;
    if (jLibraryPath) {
        JString library_path(env, jLibraryPath);
        if (!library_path.ok()) {
            return env->NewStringUTF("{\"ok\":false,\"error\":\"Failed to read the driver path\"}");
        }
        library = library_path.get();
    }
    try {
        const std::string json = fable::probe_vulkan_extensions(library);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& ex) {
        FABLE_LOGE("probeVulkanExtensions exception: %s", ex.what());
        return env->NewStringUTF("{\"ok\":false,\"error\":\"The Vulkan probe threw an exception\"}");
    }
}

JNIEXPORT jint JNICALL
Java_io_harbor_fable_nativebridge_NativeLoader_launchWineContainer(
    JNIEnv* env, jobject /*thiz*/, jstring jContainerPath, jstring jExePath, jobjectArray jArgs,
    jobjectArray jEnvVars, jstring jDriverPath, jstring jTranslator, jstring jTranslatorPath) {
    fable::set_launch_error("");
    auto fail = [](const std::string& message) {
        fable::set_launch_error(message);
        FABLE_LOGE("%s", message.c_str());
        return -1;
    };
    if (!jContainerPath || !jExePath) return fail("Container or executable path is missing");
    JString container(env, jContainerPath);
    JString exe(env, jExePath);
    if (!container.ok() || !exe.ok()) return fail("Failed to read the container or executable path");
    std::string array_error;
    std::vector<std::string> args = string_array(env, jArgs, &array_error);
    if (!array_error.empty()) return fail(array_error);
    std::vector<std::string> env_vars = string_array(env, jEnvVars, &array_error);
    if (!array_error.empty()) return fail(array_error);
    std::string driver;
    if (jDriverPath) {
        JString driver_path(env, jDriverPath);
        if (!driver_path.ok()) return fail("Failed to read the driver path");
        driver = driver_path.get();
    }
    std::string translator_name;
    if (jTranslator) {
        JString translator(env, jTranslator);
        if (!translator.ok()) return fail("Failed to read the translator name");
        translator_name = translator.get();
    }
    std::string translator_path;
    if (jTranslatorPath) {
        JString path(env, jTranslatorPath);
        if (!path.ok()) return fail("Failed to read the translator path");
        translator_path = path.get();
    }
    try {
        fable::WineLaunchRequest request;
        request.container_path = container.get();
        request.exe_path = exe.get();
        request.args = std::move(args);
        request.env = std::move(env_vars);
        request.driver_path = std::move(driver);
        request.translator = fable::parse_translator(translator_name);
        request.translator_path = std::move(translator_path);
        std::string error;
        return fable::launch_wine_container(request, &error);
    } catch (const std::exception& ex) {
        return fail(std::string("launchWineContainer exception: ") + ex.what());
    }
}

JNIEXPORT jstring JNICALL
Java_io_harbor_fable_nativebridge_NativeLoader_lastLaunchError(
    JNIEnv* env, jobject /*thiz*/) {
    const std::string message = fable::last_launch_error();
    if (message.empty()) return nullptr;
    return env->NewStringUTF(message.c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_harbor_fable_nativebridge_NativeLoader_isWineAvailable(
    JNIEnv* env, jobject /*thiz*/, jstring jWinePath) {
    if (!jWinePath) return JNI_FALSE;
    JString wine(env, jWinePath);
    if (!wine.ok()) return JNI_FALSE;
    return fable::wine_binary_available(wine.get()) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
