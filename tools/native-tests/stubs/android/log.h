#pragma once
// Doble de <android/log.h> para compilar el motor nativo en el host sin NDK.
#include <cstdarg>
#include <cstdio>

enum {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
};

inline int __android_log_print(int priority, const char* tag, const char* fmt, ...) {
    (void) priority;
    (void) tag;
    (void) fmt;
    return 0;
}

inline int __android_log_vprint(int priority, const char* tag, const char* fmt, va_list args) {
    (void) priority;
    (void) tag;
    (void) fmt;
    (void) args;
    return 0;
}

inline int __android_log_write(int priority, const char* tag, const char* text) {
    (void) priority;
    (void) tag;
    (void) text;
    return 0;
}
