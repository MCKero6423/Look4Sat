// ----------------------------------------------------------------------------
// fldigi_cw_jni.cpp  --  JNI wrapper for fldigi CW decoder (Android)
// Copyright (C) 2026 atsunatsu
// GPL v3
// ----------------------------------------------------------------------------

#include <jni.h>
#include <cstring>
#include "fldigi/cw.h"
#include "fldigi/android_compat.h"

// ============================================================================
// Global definitions (declared extern in android_compat.h / used by cw.cxx)
// ============================================================================

waterfall_stub* wf = new waterfall_stub();

AndroidProgDefaults progdefaults;
AndroidProgStatus progStatus;

bool use_nanoIO = false;
void set_nanoWPM(int wpm) {}
void set_nanoCW() {}

// UI/status stubs
void put_cwRcvWPM(int) {}
void put_MODEstatus(const char*, ...) {}
void set_scope_xaxis_1(double) {}
void set_scope_xaxis(double) {}
void update_Status() {}

// misc helpers
void set_scope_mode(int) {}
void put_rx_char(int c) {}
void put_echo_char(int c) {}

double zmsec() { return 0.0; }
void MilliSleep(int) {}

// ============================================================================
// JNI Exports
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_rtbishop_look4sat_feature_cw_FldigiNative_create(JNIEnv* env, jobject thiz) {
    cw* decoder = new cw();
    decoder->init();
    return reinterpret_cast<jlong>(decoder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rtbishop_look4sat_feature_cw_FldigiNative_destroy(JNIEnv* env, jobject thiz, jlong handle) {
    cw* decoder = reinterpret_cast<cw*>(handle);
    if (decoder) {
        decoder->rx_init();
        delete decoder;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rtbishop_look4sat_feature_cw_FldigiNative_process(JNIEnv* env, jobject thiz, jlong handle, jfloatArray audio) {
    cw* decoder = reinterpret_cast<cw*>(handle);
    if (!decoder) return;

    jsize len = env->GetArrayLength(audio);
    jfloat* elements = env->GetFloatArrayElements(audio, nullptr);
    if (!elements) return;

    // Convert float to double
    double* buf = new double[len];
    for (jsize i = 0; i < len; i++) {
        buf[i] = static_cast<double>(elements[i]);
    }

    decoder->rx_process(buf, len);

    delete[] buf;
    env->ReleaseFloatArrayElements(audio, elements, JNI_ABORT);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rtbishop_look4sat_feature_cw_FldigiNative_getDecodedText(JNIEnv* env, jobject thiz, jlong handle) {
    cw* decoder = reinterpret_cast<cw*>(handle);
    if (!decoder) return env->NewStringUTF("");

    std::string text = decoder->get_rx_text();
    return env->NewStringUTF(text.c_str());
}