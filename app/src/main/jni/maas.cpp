//
// Created by Acer on 11/21/2026.
//

#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_wiperspace_maas_MainActivity_stringFromJNi (
        JNIEnv* env,
        jobect /* this */) {
    std::string message = "Hello from c++!";
    return env->NewstringUTF(message.c_str());
}