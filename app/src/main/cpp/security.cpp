#include "security.hpp"
#include <string>
#include <cstring>
#include <strings.h>
#include <android/log.h>
#include <jni.h>
#include <vector>

#define LOG_TAG "APSecurity"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef API_TOKEN
#define API_TOKEN ""
#endif

#ifndef APP_SIGNATURE_HASH
#define APP_SIGNATURE_HASH ""
#endif

#ifndef APP_PACKAGE_NAME
#define APP_PACKAGE_NAME ""
#endif

// Helper to convert byte array to hex string
std::string bytesToHex(JNIEnv *env, jbyteArray bytes) {
    jsize length = env->GetArrayLength(bytes);
    jbyte *elements = env->GetByteArrayElements(bytes, nullptr);
    std::string out;
    out.reserve(static_cast<size_t>(length) * 2);
    static const char HEX[] = "0123456789abcdef";
    for (int i = 0; i < length; ++i) {
        unsigned char b = static_cast<unsigned char>(elements[i]);
        out.push_back(HEX[(b >> 4) & 0x0F]);
        out.push_back(HEX[b & 0x0F]);
    }
    env->ReleaseByteArrayElements(bytes, elements, JNI_ABORT);
    return out;
}

std::string getSignatureHash(JNIEnv *env, jobject context) {
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageManager = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject packageManager = env->CallObjectMethod(context, getPackageManager);
    
    jmethodID getPackageName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    jstring packageName = (jstring)env->CallObjectMethod(context, getPackageName);
    
    jclass packageManagerClass = env->GetObjectClass(packageManager);
    jmethodID getPackageInfo = env->GetMethodID(packageManagerClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    
    // GET_SIGNATURES = 64
    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfo, packageName, 64);
    
    if (packageInfo == nullptr) {
        LOGE("Failed to get PackageInfo");
        return "";
    }
    
    jclass packageInfoClass = env->GetObjectClass(packageInfo);
    jfieldID signaturesField = env->GetFieldID(packageInfoClass, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray signatures = (jobjectArray)env->GetObjectField(packageInfo, signaturesField);
    
    if (signatures == nullptr) {
        LOGE("Failed to get signatures");
        return "";
    }
    
    jobject signature = env->GetObjectArrayElement(signatures, 0);
    jclass signatureClass = env->GetObjectClass(signature);
    jmethodID toByteArray = env->GetMethodID(signatureClass, "toByteArray", "()[B");
    jbyteArray signatureBytes = (jbyteArray)env->CallObjectMethod(signature, toByteArray);
    
    // Calculate SHA-256
    jclass messageDigestClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(messageDigestClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jobject messageDigest = env->CallStaticObjectMethod(messageDigestClass, getInstance, env->NewStringUTF("SHA-256"));
    
    jmethodID digestMethod = env->GetMethodID(messageDigestClass, "digest", "([B)[B");
    jbyteArray hashBytes = (jbyteArray)env->CallObjectMethod(messageDigest, digestMethod, signatureBytes);
    
    return bytesToHex(env, hashBytes);
}

std::string getPackageName(JNIEnv *env, jobject context) {
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageNameMethod = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    jstring packageName = (jstring)env->CallObjectMethod(context, getPackageNameMethod);
    
    const char *pkgNameChars = env->GetStringUTFChars(packageName, 0);
    std::string pkgNameStr(pkgNameChars);
    env->ReleaseStringUTFChars(packageName, pkgNameChars);
    
    return pkgNameStr;
}

jstring nativeGetApiToken(JNIEnv *env, jobject thiz, jobject context) {
    if (context == nullptr) {
        LOGE("Context is null");
        return env->NewStringUTF("");
    }
    
    // 1. Verify Package Name
    std::string currentPkg = getPackageName(env, context);
    std::string expectedPkg = XSTR(APP_PACKAGE_NAME);
    
    // Simple protection: if expected is empty (not set), fail safe or warn?
    // User said "verify software package name".
    // Fail closed. With no expected package name configured there is nothing to
    // compare against, so the token must not be released; the previous code only
    // logged here and then kept going.
    if (expectedPkg.empty()) {
        LOGE("Expected package name is not set in build config; refusing to return token");
        return env->NewStringUTF("");
    }

    if (currentPkg != expectedPkg) {
        LOGE("Package name mismatch! Expected: %s, Got: %s", expectedPkg.c_str(), currentPkg.c_str());
        return env->NewStringUTF("");
    }
    
    // 2. Verify Signature
    std::string currentHash = getSignatureHash(env, context);
    std::string expectedHash = XSTR(APP_SIGNATURE_HASH); // SHA-256 hex string
    
    // Fail closed, same as the package name: an unconfigured hash means there is
    // nothing to verify against. The previous behaviour skipped verification
    // entirely whenever auth.properties was absent, which is the default for
    // every build that does not explicitly configure it.
    if (expectedHash.empty()) {
        LOGE("Expected signature hash is not set in build config; refusing to return token");
        return env->NewStringUTF("");
    }

    // An empty current hash means signature retrieval failed; never treat that
    // as a match.
    if (currentHash.empty()) {
        LOGE("Failed to compute the current signature hash; refusing to return token");
        return env->NewStringUTF("");
    }

    // Case insensitive comparison
    if (strcasecmp(currentHash.c_str(), expectedHash.c_str()) != 0) {
        LOGE("Signature mismatch! Expected: %s, Got: %s", expectedHash.c_str(), currentHash.c_str());
        return env->NewStringUTF("");
    }
    
    // 3. Return Token
    return env->NewStringUTF(XSTR(API_TOKEN));
}
