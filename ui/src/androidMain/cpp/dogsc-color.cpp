#include <jni.h>
#include <cstdint>
#include <algorithm>

extern "C" {

JNIEXPORT void JNICALL
Java_dev_dogsc_app_android_export_NativeColor_yuv420ToArgb(
        JNIEnv *env, jclass,
        jobject yBuffer, jobject uBuffer, jobject vBuffer,
        jint yRowStride, jint uRowStride, jint vRowStride,
        jint yPixelStride, jint uPixelStride, jint vPixelStride,
        jint width, jint height, jintArray outArray) {
    auto *y = static_cast<uint8_t *>(env->GetDirectBufferAddress(yBuffer));
    auto *u = static_cast<uint8_t *>(env->GetDirectBufferAddress(uBuffer));
    auto *v = static_cast<uint8_t *>(env->GetDirectBufferAddress(vBuffer));
    if (!y || !u || !v || !outArray) return;
    auto *out = static_cast<jint *>(env->GetPrimitiveArrayCritical(outArray, nullptr));
    if (!out) return;
    for (int j = 0; j < height; ++j) {
        int yRow = j * yRowStride;
        int uvRow = (j >> 1);
        for (int i = 0; i < width; ++i) {
            int yy = static_cast<int>(y[yRow + i * yPixelStride]) - 16;
            int uu = static_cast<int>(u[uvRow * uRowStride + (i >> 1) * uPixelStride]) - 128;
            int vv = static_cast<int>(v[uvRow * vRowStride + (i >> 1) * vPixelStride]) - 128;
            int r = (298 * yy + 409 * vv + 128) >> 8;
            int g = (298 * yy - 100 * uu - 208 * vv + 128) >> 8;
            int b = (298 * yy + 516 * uu + 128) >> 8;
            r = std::clamp(r, 0, 255);
            g = std::clamp(g, 0, 255);
            b = std::clamp(b, 0, 255);
            out[j * width + i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    }
    env->ReleasePrimitiveArrayCritical(outArray, out, 0);
}

JNIEXPORT void JNICALL
Java_dev_dogsc_app_android_export_NativeColor_argbToNv12(
        JNIEnv *env, jclass,
        jintArray argbArray, jbyteArray outArray, jint width, jint height) {
    if (!argbArray || !outArray) return;
    auto *argb = static_cast<jint *>(env->GetPrimitiveArrayCritical(argbArray, nullptr));
    auto *out = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(outArray, nullptr));
    if (!argb || !out) {
        if (argb) env->ReleasePrimitiveArrayCritical(argbArray, argb, JNI_ABORT);
        return;
    }
    int frameSize = width * height;
    int yIndex = 0;
    int uvIndex = frameSize;
    for (int j = 0; j < height; ++j) {
        for (int i = 0; i < width; ++i) {
            int color = argb[j * width + i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            out[yIndex++] = static_cast<jbyte>(std::clamp(y, 0, 255));
            if ((j & 1) == 0 && (i & 1) == 0) {
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                out[uvIndex++] = static_cast<jbyte>(std::clamp(u, 0, 255));
                out[uvIndex++] = static_cast<jbyte>(std::clamp(v, 0, 255));
            }
        }
    }
    env->ReleasePrimitiveArrayCritical(outArray, out, 0);
    env->ReleasePrimitiveArrayCritical(argbArray, argb, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_dev_dogsc_app_android_export_NativeColor_argbToI420(
        JNIEnv *env, jclass,
        jintArray argbArray, jbyteArray outArray, jint width, jint height) {
    if (!argbArray || !outArray) return;
    auto *argb = static_cast<jint *>(env->GetPrimitiveArrayCritical(argbArray, nullptr));
    auto *out = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(outArray, nullptr));
    if (!argb || !out) {
        if (argb) env->ReleasePrimitiveArrayCritical(argbArray, argb, JNI_ABORT);
        return;
    }
    int frameSize = width * height;
    int yIndex = 0;
    int uIndex = frameSize;
    int vIndex = frameSize + frameSize / 4;
    for (int j = 0; j < height; ++j) {
        for (int i = 0; i < width; ++i) {
            int color = argb[j * width + i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            out[yIndex++] = static_cast<jbyte>(std::clamp(y, 0, 255));
            if ((j & 1) == 0 && (i & 1) == 0) {
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                out[uIndex++] = static_cast<jbyte>(std::clamp(u, 0, 255));
                out[vIndex++] = static_cast<jbyte>(std::clamp(v, 0, 255));
            }
        }
    }
    env->ReleasePrimitiveArrayCritical(outArray, out, 0);
    env->ReleasePrimitiveArrayCritical(argbArray, argb, JNI_ABORT);
}

}
