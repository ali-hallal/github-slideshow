// The pixel-building step of the sensor-photo merge on the graphics processor (OpenGL ES 3.1
// compute, which nearly every Android phone has). It does exactly what the compiled
// Native.build does, one group of filter samples per shader invocation. Anything missing or
// failing — no ES 3.1, too little buffer room, a driver error — returns NaN and the caller uses
// the processor instead, so a photo never depends on it.
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <cstdio>
#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <cmath>
#include <cstdint>
#include <cstring>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AnyCam", __VA_ARGS__)
#else
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif

namespace {

const char* SHADER = R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = 8, local_size_y = 8) in;
layout(std430, binding = 0) readonly buffer Light { float light[]; };
layout(std430, binding = 1) readonly buffer Frames { uint frames[]; };
layout(std430, binding = 2) readonly buffer Other { uint other[]; };
layout(std430, binding = 3) writeonly buffer Out { uint outv[]; };

uniform int W, H, PW, COLS, TILE, N, LEVELS, K, SHC, SHR, ROW0;
uniform int frameStride, oTrust, trustStride, oPlace, placeStride, oBusy, oGreen, oGW, oCW, oGamma, oLook, oShading;
uniform int oU, oV, oWeights, gammaSteps;
uniform ivec4 colourAt;
uniform ivec4 besideIsRed;
uniform vec4 black;
uniform float scale;
uniform vec3 gain;
uniform float ccm[9];
uniform float lookColour[12];
uniform float fillBase, busyFill;

uint byteAt(int off, int i) { return (other[off + (i >> 2)] >> uint((i & 3) * 8)) & 255u; }
uint shortOther(int off, int i) { return (other[off + (i >> 1)] >> uint((i & 1) * 16)) & 65535u; }
uint frameShort(int k, int i) { return (frames[k * frameStride + (i >> 1)] >> uint((i & 1) * 16)) & 65535u; }
float tableF(int i) { return uintBitsToFloat(other[i]); }

vec3 pixel(int x, int y, int own, bool bir) {
    int s = W + 4;
    int i = (y + 2) * s + x + 2;
    float c = light[i];
    float cross = light[i - 1] + light[i + 1] + light[i - s] + light[i + s];
    float diagonal = light[i - s - 1] + light[i - s + 1] + light[i + s - 1] + light[i + s + 1];
    float farH = light[i - 2] + light[i + 2];
    float farV = light[i - 2 * s] + light[i + 2 * s];
    if (own == 1) {
        float beside = max(0.0, (5.0 * c + 4.0 * (light[i - 1] + light[i + 1]) - diagonal - farH + 0.5 * farV) / 8.0);
        float above = max(0.0, (5.0 * c + 4.0 * (light[i - s] + light[i + s]) - diagonal - farV + 0.5 * farH) / 8.0);
        return bir ? vec3(beside, c, above) : vec3(above, c, beside);
    }
    float green = max(0.0, (4.0 * c + 2.0 * cross - farH - farV) / 8.0);
    float otherC = max(0.0, (6.0 * c + 2.0 * diagonal - 1.5 * (farH + farV)) / 8.0);
    return own == 0 ? vec3(c, green, otherC) : vec3(otherC, green, c);
}

void main() {
    int cy = int(gl_GlobalInvocationID.y) + ROW0;
    int cx0 = int(gl_GlobalInvocationID.x) * 4;
    if (cy >= H / 2 || cx0 >= W / 2) return;
    uint ys[4] = uint[4](0u, 0u, 0u, 0u);
    uint us = 0u, vs = 0u;
    float rowWeight = 0.0;
    bool hasShading = SHC >= 2 && SHR >= 2;
    int shRow = 0;
    float shAy = 0.0;
    if (hasShading) {
        float fy = (float(cy * 2) + 0.5) / (float(H) - 1.0) * float(SHR - 1);
        shRow = clamp(int(fy), 0, SHR - 2);
        shAy = clamp(fy - float(shRow), 0.0, 1.0);
    }
    for (int g = 0; g < 4; g++) {
        int cx = cx0 + g;
        int tile = (cy / TILE) * COLS + cx / TILE;
        vec3 lens = vec3(1.0);
        if (hasShading) {
            float fx = (float(cx * 2) + 0.5) / (float(W) - 1.0) * float(SHC - 1);
            int x0 = clamp(int(fx), 0, SHC - 2);
            float ax = clamp(fx - float(x0), 0.0, 1.0);
            int o0 = oShading + (shRow * SHC + x0) * 3;
            int o1 = o0 + SHC * 3;
            for (int c = 0; c < 3; c++)
                lens[c] = (tableF(o0 + c) * (1.0 - ax) + tableF(o0 + 3 + c) * ax) * (1.0 - shAy) +
                          (tableF(o1 + c) * (1.0 - ax) + tableF(o1 + 3 + c) * ax) * shAy;
        }
        float su = 0.0, sv = 0.0;
        int level = int(byteAt(oBusy, cy * PW + cx));
        if (level > 127) level -= 256;
        float fill = fillBase + float(level) * busyFill;
        for (int p = 0; p < 4; p++) {
            int x = cx * 2 + p % 2;
            int y = cy * 2 + p / 2;
            int own = colourAt[p];
            vec3 rgb = pixel(x, y, own, besideIsRed[p] != 0);
            vec3 sum = vec3(0.0), weight = vec3(0.0);
            sum[own] = own == 1 ? rgb.g : rgb[own] - rgb.g;
            weight[own] = 1.0;
            for (int k = 0; k < N; k++) {
                uint t = byteAt(oTrust + k * trustStride, cy * PW + cx);
                if (t == 0u) continue;
                int pl = oPlace + k * placeStride + 3 * tile;
                int ix = x + int(other[pl]);
                int iy = y + int(other[pl + 1]);
                if (ix < 1 || iy < 1 || ix >= W - 1 || iy >= H - 1) continue;
                int at = int(other[pl + 2]);
                float tw = float(t) / 255.0;
                for (int j = -1; j <= 1; j++) {
                    int rowPos = ((iy + j) & 1) * 2;
                    for (int i = -1; i <= 1; i++) {
                        int pos = rowPos + ((ix + i) & 1);
                        int c = colourAt[pos];
                        int kk = (j + 1) * 3 + i + 1;
                        float wt = tw * (c == 1 ? tableF(oGW + level * K + at + kk) : tableF(oCW + at + kk));
                        if (wt < 0.01) continue;
                        float v = (float(frameShort(k, (iy + j) * W + ix + i)) - black[pos]) * scale;
                        if (v < 0.0) v = 0.0;
                        v *= lens[c] * gain[c];
                        if (v > 1.0) v = 1.0;
                        if (c != 1) {
                            int gx = clamp(x + i, 0, W - 1);
                            int gy = clamp(y + j, 0, H - 1);
                            v -= float(shortOther(oGreen, gy * W + gx)) / 65535.0;
                        }
                        sum[c] += v * wt;
                        weight[c] += wt;
                    }
                }
            }
            rowWeight += weight.g;
            float gg = (sum.g + fill * rgb.g) / (weight.g + fill);
            float r = max(0.0, gg + (sum.r + fillBase * (rgb.r - rgb.g)) / (weight.r + fillBase));
            float b = max(0.0, gg + (sum.b + fillBase * (rgb.b - rgb.g)) / (weight.b + fillBase));
            float lr = clamp(ccm[0] * r + ccm[1] * gg + ccm[2] * b, 0.0, 1.0);
            float lg = clamp(ccm[3] * r + ccm[4] * gg + ccm[5] * b, 0.0, 1.0);
            float lb = clamp(ccm[6] * r + ccm[7] * gg + ccm[8] * b, 0.0, 1.0);
            float dr = tableF(oGamma + int(lr * float(gammaSteps)));
            float dg = tableF(oGamma + int(lg * float(gammaSteps)));
            float db = tableF(oGamma + int(lb * float(gammaSteps)));
            float lift = tableF(oLook + clamp(int(0.299 * dr + 0.587 * dg + 0.114 * db), 0, 255));
            dr *= lift; dg *= lift; db *= lift;
            float fr2 = clamp(lookColour[0] * dr + lookColour[1] * dg + lookColour[2] * db + lookColour[3], 0.0, 255.0);
            float fg2 = clamp(lookColour[4] * dr + lookColour[5] * dg + lookColour[6] * db + lookColour[7], 0.0, 255.0);
            float fb2 = clamp(lookColour[8] * dr + lookColour[9] * dg + lookColour[10] * db + lookColour[11], 0.0, 255.0);
            uint yv = uint(clamp(int(0.299 * fr2 + 0.587 * fg2 + 0.114 * fb2 + 0.5), 0, 255));
            // Pixels x..x+7 of rows 2cy and 2cy+1: two words per row.
            int col = g * 2 + p % 2;
            int word = (p / 2) * 2 + col / 4;
            ys[word] |= yv << uint((col % 4) * 8);
            su += -0.168736 * fr2 - 0.331264 * fg2 + 0.5 * fb2;
            sv += 0.5 * fr2 - 0.418688 * fg2 - 0.081312 * fb2;
        }
        us |= uint(clamp(int(su / 4.0 + 128.5), 0, 255)) << uint(g * 8);
        vs |= uint(clamp(int(sv / 4.0 + 128.5), 0, 255)) << uint(g * 8);
    }
    int y0 = cy * 2;
    int x0 = cx0 * 2;
    outv[(y0 * W + x0) / 4] = ys[0];
    outv[(y0 * W + x0) / 4 + 1] = ys[1];
    outv[((y0 + 1) * W + x0) / 4] = ys[2];
    outv[((y0 + 1) * W + x0) / 4 + 1] = ys[3];
    outv[oU + (cy * (W / 2) + cx0) / 4] = us;
    outv[oV + (cy * (W / 2) + cx0) / 4] = vs;
    outv[oWeights + cy * (W / 8) + cx0 / 4] = floatBitsToUint(rowWeight);
}
)";

std::mutex lock;
EGLDisplay display = EGL_NO_DISPLAY;
EGLContext context = EGL_NO_CONTEXT;
EGLSurface surface = EGL_NO_SURFACE;
GLuint program = 0;
bool broken = false;
std::string why;
std::string timings = "";
double ms(std::chrono::steady_clock::time_point a, std::chrono::steady_clock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

bool setUp() {
    if (program) return true;
    if (broken) return false;
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) { why = "no display"; return false; }
    EGLint attrs[] = {EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    eglChooseConfig(display, attrs, &config, 1, &count);
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint version[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context = eglCreateContext(display, count ? config : (EGLConfig) nullptr, EGL_NO_CONTEXT, version);
    if (context == EGL_NO_CONTEXT) { why = "no context"; return false; }
    // A tiny off-screen surface where the driver wants one; none where it doesn't.
    if (count) {
        EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface = eglCreatePbufferSurface(display, config, size);
    }
    if (!eglMakeCurrent(display, surface, surface, context)) { why = "can't make current"; return false; }
    const char* ver = (const char*) glGetString(GL_VERSION);
    int major = 0, minor = 0;
    if (!ver || sscanf(ver, "OpenGL ES %d.%d", &major, &minor) != 2 || major * 10 + minor < 31) { why = std::string("version ") + (ver ? ver : "?"); return false; }
    GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
    glShaderSource(shader, 1, &SHADER, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[2048];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        why = std::string("compile: ") + log;
        return false;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, shader);
    glLinkProgram(p);
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) { why = "link"; return false; }
    program = p;
    LOGI("graphics processor ready: %s, %s", ver, (const char*) glGetString(GL_RENDERER));
    return true;
}

void put(std::vector<uint32_t>& v, const void* data, size_t bytes) {
    size_t at = v.size();
    v.resize(at + (bytes + 3) / 4, 0);
    memcpy(v.data() + at, data, bytes);
}

}  // namespace

std::string gpuStatus() { return program ? "ready" + timings : (why.empty() ? "unused" : why); }

extern "C" JNIEXPORT jstring JNICALL Java_com_anycam_app_core_Native_gpuStatus(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> g(lock);
    return env->NewStringUTF(gpuStatus().c_str());
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_anycam_app_core_Native_buildGpu(
    JNIEnv* env, jclass,
    jint w, jint h, jint pw, jint cols, jint tileSize,
    jfloatArray jLight, jobjectArray jFrames, jobjectArray jTrust, jobjectArray jPlace,
    jbyteArray jBusy, jshortArray jGreen, jobjectArray jGreenWeights, jfloatArray jColourWeights,
    jintArray jColourAt, jbooleanArray jBesideIsRed, jfloatArray jBlack, jfloat scale, jfloatArray jGain,
    jint shCols, jint shRows, jfloatArray jShading,
    jfloatArray jCcm, jfloatArray jGamma, jfloatArray jLookGain, jfloatArray jLookColour,
    jfloat fillBase, jfloat busyFill, jint steps,
    jbyteArray jOutY, jbyteArray jOutU, jbyteArray jOutV) {
    std::lock_guard<std::mutex> g(lock);
    const double FAILED = NAN;
    if (w % 8 != 0 || h % 2 != 0) return FAILED;
    auto t0 = std::chrono::steady_clock::now();
    const bool fresh = program == 0;
    if (!setUp()) { if (!broken) LOGI("graphics processor not used: %s", why.c_str()); broken = true; return FAILED; }
    if (!eglMakeCurrent(display, surface, surface, context)) return FAILED;
    const int n = env->GetArrayLength(jFrames);
    const int levels = env->GetArrayLength(jGreenWeights);
    // Everything but the light and the frames goes into one buffer (some phones allow only four).
    std::vector<uint32_t> other;
    auto putArray = [&](jarray a, size_t elem) -> int {
        int at = (int) other.size();
        if (!a) return at;
        void* p = env->GetPrimitiveArrayCritical(a, nullptr);
        put(other, p, env->GetArrayLength(a) * elem);
        env->ReleasePrimitiveArrayCritical(a, p, JNI_ABORT);
        return at;
    };
    const int trustStride = (env->GetArrayLength((jbyteArray) env->GetObjectArrayElement(jTrust, 0)) + 3) / 4;
    int oTrust = (int) other.size();
    for (int k = 0; k < n; k++) {
        int at = putArray((jarray) env->GetObjectArrayElement(jTrust, k), 1);
        other.resize(at + trustStride, 0);
    }
    int placeStride = env->GetArrayLength((jintArray) env->GetObjectArrayElement(jPlace, 0));
    int oPlace = (int) other.size();
    for (int k = 0; k < n; k++) putArray((jarray) env->GetObjectArrayElement(jPlace, k), 4);
    int oBusy = putArray(jBusy, 1);
    int oGreen = putArray(jGreen, 2);
    int K = env->GetArrayLength((jfloatArray) env->GetObjectArrayElement(jGreenWeights, 0));
    int oGW = (int) other.size();
    for (int l = 0; l < levels; l++) putArray((jarray) env->GetObjectArrayElement(jGreenWeights, l), 4);
    int oCW = putArray(jColourWeights, 4);
    int oGamma = putArray(jGamma, 4);
    int gammaSteps = env->GetArrayLength(jGamma) - 1;
    int oLook = putArray(jLookGain, 4);
    int oShading = putArray(jShading, 4);

    GLint64 maxBlock = 0;
    glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &maxBlock);
    const size_t frameStride = ((size_t) w * h + 1) / 2;
    const size_t lightBytes = (size_t) env->GetArrayLength(jLight) * 4;
    const size_t frameBytes = frameStride * 4 * n;
    const size_t outWords = (size_t) w * h / 4 + 2 * ((size_t) w * h / 16) + (size_t) (w / 8) * (h / 2);
    if ((GLint64) lightBytes > maxBlock || (GLint64) frameBytes > maxBlock || (GLint64) other.size() * 4 > maxBlock || (GLint64) outWords * 4 > maxBlock) {
        LOGI("graphics processor not used: buffers too large for this phone (%lld)", (long long) maxBlock);
        return FAILED;
    }
    auto t1 = std::chrono::steady_clock::now();
    GLuint buffers[4];
    glGenBuffers(4, buffers);
    void* p = env->GetPrimitiveArrayCritical(jLight, nullptr);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[0]);
    glBufferData(GL_SHADER_STORAGE_BUFFER, lightBytes, p, GL_STATIC_DRAW);
    env->ReleasePrimitiveArrayCritical(jLight, p, JNI_ABORT);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[1]);
    glBufferData(GL_SHADER_STORAGE_BUFFER, frameBytes, nullptr, GL_STATIC_DRAW);
    for (int k = 0; k < n; k++) {
        jshortArray a = (jshortArray) env->GetObjectArrayElement(jFrames, k);
        void* q = env->GetPrimitiveArrayCritical(a, nullptr);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, frameStride * 4 * k, (size_t) w * h * 2, q);
        env->ReleasePrimitiveArrayCritical(a, q, JNI_ABORT);
    }
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[2]);
    glBufferData(GL_SHADER_STORAGE_BUFFER, other.size() * 4, other.data(), GL_STATIC_DRAW);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[3]);
    glBufferData(GL_SHADER_STORAGE_BUFFER, outWords * 4, nullptr, GL_DYNAMIC_READ);
    for (int i = 0; i < 4; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, buffers[i]);

    glFinish();
    auto t2 = std::chrono::steady_clock::now();
    glUseProgram(program);
    auto u = [&](const char* name) { return glGetUniformLocation(program, name); };
    glUniform1i(u("W"), w); glUniform1i(u("H"), h); glUniform1i(u("PW"), pw); glUniform1i(u("COLS"), cols);
    glUniform1i(u("TILE"), tileSize); glUniform1i(u("N"), n); glUniform1i(u("LEVELS"), levels); glUniform1i(u("K"), K);
    glUniform1i(u("SHC"), shCols); glUniform1i(u("SHR"), shRows);
    glUniform1i(u("frameStride"), (GLint) frameStride); glUniform1i(u("oTrust"), oTrust); glUniform1i(u("trustStride"), trustStride);
    glUniform1i(u("oPlace"), oPlace); glUniform1i(u("placeStride"), placeStride); glUniform1i(u("oBusy"), oBusy);
    glUniform1i(u("oGreen"), oGreen); glUniform1i(u("oGW"), oGW); glUniform1i(u("oCW"), oCW); glUniform1i(u("oGamma"), oGamma);
    glUniform1i(u("oLook"), oLook); glUniform1i(u("oShading"), oShading); glUniform1i(u("gammaSteps"), gammaSteps);
    const int oU = w * h / 4, oV = oU + w * h / 16, oWeights = oV + w * h / 16;
    glUniform1i(u("oU"), oU); glUniform1i(u("oV"), oV); glUniform1i(u("oWeights"), oWeights);
    jint colourAt[4];
    env->GetIntArrayRegion(jColourAt, 0, 4, colourAt);
    jboolean bir[4];
    env->GetBooleanArrayRegion(jBesideIsRed, 0, 4, bir);
    glUniform4i(u("colourAt"), colourAt[0], colourAt[1], colourAt[2], colourAt[3]);
    glUniform4i(u("besideIsRed"), bir[0], bir[1], bir[2], bir[3]);
    float black[4], gain[3], ccm[9], look[12];
    env->GetFloatArrayRegion(jBlack, 0, 4, black);
    env->GetFloatArrayRegion(jGain, 0, 3, gain);
    env->GetFloatArrayRegion(jCcm, 0, 9, ccm);
    env->GetFloatArrayRegion(jLookColour, 0, 12, look);
    glUniform4f(u("black"), black[0], black[1], black[2], black[3]);
    glUniform1f(u("scale"), scale);
    glUniform3f(u("gain"), gain[0], gain[1], gain[2]);
    glUniform1fv(u("ccm"), 9, ccm);
    glUniform1fv(u("lookColour"), 12, look);
    glUniform1f(u("fillBase"), fillBase);
    glUniform1f(u("busyFill"), busyFill);
    // In bands, so no single job runs long enough for a phone's watchdog to stop it.
    const int across = (w / 8 + 7) / 8;
    const int band = 64;
    for (int row0 = 0; row0 < h / 2; row0 += band * 8) {
        glUniform1i(u("ROW0"), row0);
        glDispatchCompute(across, std::min(band, (h / 2 - row0 + 7) / 8), 1);
        glFlush();
    }
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    glFinish();
    auto t3 = std::chrono::steady_clock::now();
    double total = NAN;
    const uint32_t* out = (const uint32_t*) glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, outWords * 4, GL_MAP_READ_BIT);
    if (out && glGetError() == GL_NO_ERROR) {
        env->SetByteArrayRegion(jOutY, 0, w * h, (const jbyte*) out);
        env->SetByteArrayRegion(jOutU, 0, w * h / 4, (const jbyte*) (out + oU));
        env->SetByteArrayRegion(jOutV, 0, w * h / 4, (const jbyte*) (out + oV));
        total = 0;
        const float* weights = (const float*) (out + oWeights);
        for (size_t i = 0; i < (size_t) (w / 8) * (h / 2); i++) total += weights[i];
    } else {
        LOGI("graphics processor failed (%x)", glGetError());
    }
    if (out) glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    glDeleteBuffers(4, buffers);
    auto t4 = std::chrono::steady_clock::now();
    char text[200];
    snprintf(text, sizeof(text), " (prepare %s%.0f, upload %.0f, compute %.0f, read back %.0f ms)",
        fresh ? "incl. first-time compile " : "", ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4));
    timings = text;
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return total;
}
