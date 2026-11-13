// The heaviest steps of the sensor-photo merge (Stack.kt), as compiled code. Each function
// mirrors its Kotlin counterpart line for line; the Kotlin one stays the reference and the
// fallback. Plain C++ and threads: the same on every Android phone.
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

namespace {

template <typename F>
void rows(int count, F body) {
    int threads = (int)std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    std::atomic<int> next(0);
    std::vector<std::thread> pool;
    for (int t = 0; t < std::min(threads, count); t++) {
        pool.emplace_back([&]() {
            for (;;) {
                int r = next.fetch_add(1);
                if (r >= count) break;
                body(r);
            }
        });
    }
    for (auto& th : pool) th.join();
}

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Develop.pixel: red, green and blue at one pixel from the merged light (2-pixel border, stride w+4).
inline void pixel(const float* l, int s, int x, int y, int own, bool besideIsRed, float* out) {
    int i = (y + 2) * s + x + 2;
    float c = l[i];
    float cross = l[i - 1] + l[i + 1] + l[i - s] + l[i + s];
    float diagonal = l[i - s - 1] + l[i - s + 1] + l[i + s - 1] + l[i + s + 1];
    float farH = l[i - 2] + l[i + 2];
    float farV = l[i - 2 * s] + l[i + 2 * s];
    if (own == 1) {
        float beside = std::max(0.f, (5 * c + 4 * (l[i - 1] + l[i + 1]) - diagonal - farH + 0.5f * farV) / 8.f);
        float above = std::max(0.f, (5 * c + 4 * (l[i - s] + l[i + s]) - diagonal - farV + 0.5f * farH) / 8.f);
        out[0] = besideIsRed ? beside : above;
        out[1] = c;
        out[2] = besideIsRed ? above : beside;
    } else {
        float green = std::max(0.f, (4 * c + 2 * cross - farH - farV) / 8.f);
        float other = std::max(0.f, (6 * c + 2 * diagonal - 1.5f * (farH + farV)) / 8.f);
        out[0] = own == 0 ? c : other;
        out[1] = green;
        out[2] = own == 0 ? other : c;
    }
}

template <typename T>
struct Pinned {
    JNIEnv* env;
    jarray array;
    T* data;
    Pinned(JNIEnv* e, jarray a) : env(e), array(a), data(a ? (T*)e->GetPrimitiveArrayCritical(a, nullptr) : nullptr) {}
    ~Pinned() { if (array) env->ReleasePrimitiveArrayCritical(array, data, 0); }
};

}  // namespace

extern "C" JNIEXPORT jdouble JNICALL
Java_com_anycam_app_core_Native_build(
    JNIEnv* env, jclass,
    jint w, jint h, jint pw, jint cols, jint tileSize,
    jfloatArray jLight, jobjectArray jFrames, jobjectArray jTrust, jobjectArray jExact,
    jbyteArray jBusy, jshortArray jGreen, jobjectArray jGreenWeights, jfloatArray jColourWeights,
    jintArray jColourAt, jbooleanArray jBesideIsRed, jfloatArray jBlack, jfloat scale, jfloatArray jGain,
    jint shCols, jint shRows, jfloatArray jShading,
    jfloatArray jCcm, jfloatArray jGamma, jfloatArray jLookGain, jfloatArray jLookColour,
    jfloat fillBase, jfloat busyFill, jint steps,
    jbyteArray jOutY, jbyteArray jOutU, jbyteArray jOutV) {
    const int n = env->GetArrayLength(jFrames);
    const int levels = env->GetArrayLength(jGreenWeights);
    // Small tables: copied (they must be read while other arrays are pinned).
    auto copyF = [&](jfloatArray a) { std::vector<float> v(a ? env->GetArrayLength(a) : 0); if (a) env->GetFloatArrayRegion(a, 0, v.size(), v.data()); return v; };
    std::vector<std::vector<float>> greenWeights(levels), exact(n);
    for (int l = 0; l < levels; l++) greenWeights[l] = copyF((jfloatArray)env->GetObjectArrayElement(jGreenWeights, l));
    for (int k = 0; k < n; k++) exact[k] = copyF((jfloatArray)env->GetObjectArrayElement(jExact, k));
    std::vector<float> colourWeights = copyF(jColourWeights), black = copyF(jBlack), gain = copyF(jGain), shading = copyF(jShading),
        ccm = copyF(jCcm), gamma = copyF(jGamma), lookGain = copyF(jLookGain), a = copyF(jLookColour);
    int colourAt[4];
    env->GetIntArrayRegion(jColourAt, 0, 4, colourAt);
    jboolean bir[4];
    env->GetBooleanArrayRegion(jBesideIsRed, 0, 4, bir);
    std::vector<jshortArray> frameArrays(n);
    std::vector<jbyteArray> trustArrays(n);
    for (int k = 0; k < n; k++) {
        frameArrays[k] = (jshortArray)env->GetObjectArrayElement(jFrames, k);
        trustArrays[k] = (jbyteArray)env->GetObjectArrayElement(jTrust, k);
    }
    const int gammaSteps = (int)gamma.size() - 1;
    const bool hasShading = shCols >= 2 && shRows >= 2 && !shading.empty();
    const int s = w + 4;
    std::vector<double> rowWeights(h / 2, 0.0);
    {
        Pinned<float> light(env, jLight);
        Pinned<int8_t> busy(env, jBusy);
        Pinned<uint16_t> green(env, jGreen);
        Pinned<uint8_t> outY(env, jOutY), outU(env, jOutU), outV(env, jOutV);
        std::vector<Pinned<uint16_t>*> frames;
        std::vector<Pinned<uint8_t>*> trust;
        for (int k = 0; k < n; k++) {
            frames.push_back(new Pinned<uint16_t>(env, frameArrays[k]));
            trust.push_back(new Pinned<uint8_t>(env, trustArrays[k]));
        }
        const int kw = (steps + 1);
        rows(h / 2, [&](int cy) {
            float rgb[3], sum[3], weight[3], lens[3] = {1, 1, 1};
            double rowWeight = 0;
            int shRow = 0;
            float shAy = 0;
            if (hasShading) {
                float fy = (cy * 2 + 0.5f) / (h - 1.f) * (shRows - 1);
                shRow = std::clamp((int)fy, 0, shRows - 2);
                shAy = clampf(fy - shRow, 0.f, 1.f);
            }
            for (int cx = 0; cx < w / 2; cx++) {
                const int tile = (cy / tileSize) * cols + cx / tileSize;
                if (hasShading) {
                    float fx = (cx * 2 + 0.5f) / (w - 1.f) * (shCols - 1);
                    int x0 = std::clamp((int)fx, 0, shCols - 2);
                    float ax = clampf(fx - x0, 0.f, 1.f);
                    int o0 = (shRow * shCols + x0) * 3;
                    int o1 = o0 + shCols * 3;
                    for (int c = 0; c < 3; c++)
                        lens[c] = (shading[o0 + c] * (1 - ax) + shading[o0 + 3 + c] * ax) * (1 - shAy) +
                                  (shading[o1 + c] * (1 - ax) + shading[o1 + 3 + c] * ax) * shAy;
                }
                float su = 0, sv = 0;
                const int level = busy.data[cy * pw + cx];
                const float fill = fillBase + level * busyFill;
                const float* tight = greenWeights[level].data();
                const float* wide = colourWeights.data();
                for (int p = 0; p < 4; p++) {
                    const int x = cx * 2 + p % 2;
                    const int y = cy * 2 + p / 2;
                    pixel(light.data, s, x, y, colourAt[p], bir[p], rgb);
                    const int own = colourAt[p];
                    sum[0] = sum[1] = sum[2] = 0;
                    weight[0] = weight[1] = weight[2] = 0;
                    sum[own] = own == 1 ? rgb[1] : rgb[own] - rgb[1];
                    weight[own] = 1;
                    for (int k = 0; k < n; k++) {
                        const int t = trust[k]->data[cy * pw + cx];
                        if (t == 0) continue;
                        const float px = x + exact[k][2 * tile];
                        const float py = y + exact[k][2 * tile + 1];
                        const int ix = (int)std::floor(px + 0.5f);
                        const int iy = (int)std::floor(py + 0.5f);
                        if (ix < 1 || iy < 1 || ix >= w - 1 || iy >= h - 1) continue;
                        const int at = (std::clamp((int)((py - iy + 0.5f) * steps), 0, steps) * kw +
                                        std::clamp((int)((px - ix + 0.5f) * steps), 0, steps)) * 9;
                        const uint16_t* fr = frames[k]->data;
                        const float tw = t / 255.f;
                        for (int j = -1; j <= 1; j++) {
                            const int row = (iy + j) * w;
                            const int rowPos = ((iy + j) & 1) * 2;
                            for (int i = -1; i <= 1; i++) {
                                const int pos = rowPos + ((ix + i) & 1);
                                const int c = colourAt[pos];
                                const int kk = (j + 1) * 3 + i + 1;
                                const float wt = tw * (c == 1 ? tight[at + kk] : wide[at + kk]);
                                if (wt < 0.01f) continue;
                                float v = (fr[row + ix + i] - black[pos]) * scale;
                                if (v < 0) v = 0;
                                v *= lens[c] * gain[c];
                                if (v > 1) v = 1;
                                if (c != 1) {
                                    const int gx = std::clamp(x + i, 0, w - 1);
                                    const int gy = std::clamp(y + j, 0, h - 1);
                                    v -= green.data[gy * w + gx] / 65535.f;
                                }
                                sum[c] += v * wt;
                                weight[c] += wt;
                            }
                        }
                    }
                    rowWeight += weight[1];
                    const float g = (sum[1] + fill * rgb[1]) / (weight[1] + fill);
                    const float r = std::max(0.f, g + (sum[0] + fillBase * (rgb[0] - rgb[1])) / (weight[0] + fillBase));
                    const float b = std::max(0.f, g + (sum[2] + fillBase * (rgb[2] - rgb[1])) / (weight[2] + fillBase));
                    const float lr = clampf(ccm[0] * r + ccm[1] * g + ccm[2] * b, 0, 1);
                    const float lg = clampf(ccm[3] * r + ccm[4] * g + ccm[5] * b, 0, 1);
                    const float lb = clampf(ccm[6] * r + ccm[7] * g + ccm[8] * b, 0, 1);
                    float dr = gamma[(int)(lr * gammaSteps)];
                    float dg = gamma[(int)(lg * gammaSteps)];
                    float db = gamma[(int)(lb * gammaSteps)];
                    const float lift = lookGain[std::clamp((int)(0.299f * dr + 0.587f * dg + 0.114f * db), 0, 255)];
                    dr *= lift;
                    dg *= lift;
                    db *= lift;
                    const float fr2 = clampf(a[0] * dr + a[1] * dg + a[2] * db + a[3], 0, 255);
                    const float fg2 = clampf(a[4] * dr + a[5] * dg + a[6] * db + a[7], 0, 255);
                    const float fb2 = clampf(a[8] * dr + a[9] * dg + a[10] * db + a[11], 0, 255);
                    outY.data[y * w + x] = (uint8_t)std::clamp((int)(0.299f * fr2 + 0.587f * fg2 + 0.114f * fb2 + 0.5f), 0, 255);
                    su += -0.168736f * fr2 - 0.331264f * fg2 + 0.5f * fb2;
                    sv += 0.5f * fr2 - 0.418688f * fg2 - 0.081312f * fb2;
                }
                outU.data[cy * (w / 2) + cx] = (uint8_t)std::clamp((int)(su / 4 + 128.5f), 0, 255);
                outV.data[cy * (w / 2) + cx] = (uint8_t)std::clamp((int)(sv / 4 + 128.5f), 0, 255);
            }
            rowWeights[cy] = rowWeight;
        });
        for (auto* p : trust) delete p;
        for (auto* p : frames) delete p;
    }
    double total = 0;
    for (double r : rowWeights) total += r;
    return total;
}

// The per-group trust of one frame (before the neighbourhood calming), as in Stack.kt.
extern "C" JNIEXPORT void JNICALL
Java_com_anycam_app_core_Native_trust(
    JNIEnv* env, jclass,
    jint w, jint h, jint pw, jint ph, jint cols, jint tileSize, jboolean frameOk, jfloat tileLimit,
    jbyteArray jRefSoft, jbyteArray jFrameSoft, jintArray jDx, jintArray jDy, jfloatArray jCost, jfloatArray jExact,
    jshortArray jRef, jshortArray jFrame, jfloatArray jGreyOf, jfloatArray jSampleNoise, jbyteArray jOut) {
    if (!frameOk) return;
    auto copyF = [&](jfloatArray a) { std::vector<float> v(env->GetArrayLength(a)); env->GetFloatArrayRegion(a, 0, v.size(), v.data()); return v; };
    auto copyI = [&](jintArray a) { std::vector<int> v(env->GetArrayLength(a)); env->GetIntArrayRegion(a, 0, v.size(), v.data()); return v; };
    std::vector<float> cost = copyF(jCost), exact = copyF(jExact), greyOf = copyF(jGreyOf), noise = copyF(jSampleNoise);
    std::vector<int> dx = copyI(jDx), dy = copyI(jDy);
    const int top = (int)greyOf.size() - 1;
    Pinned<uint8_t> refSoft(env, jRefSoft), frameSoft(env, jFrameSoft), out(env, jOut);
    Pinned<uint16_t> ref(env, jRef), frame(env, jFrame);
    auto grey4 = [&](const uint16_t* f, int x, int y) {
        int o = y * w + x;
        int v = (f[o] + f[o + 1] + f[o + w] + f[o + w + 1]) >> 2;
        return greyOf[v > top ? top : v];
    };
    rows(ph, [&](int qy) {
        for (int qx = 0; qx < pw; qx++) {
            const int tile = (qy / tileSize) * cols + qx / tileSize;
            if (cost[tile] > tileLimit) continue;
            const int fx = qx + dx[tile];
            const int fy = qy + dy[tile];
            if (fx < 1 || fy < 1 || fx >= pw - 1 || fy >= ph - 1 || qx < 1 || qy < 1 || qx >= pw - 1 || qy >= ph - 1) continue;
            const int i = qy * pw + qx;
            const int r = refSoft.data[i];
            const int d = std::abs(frameSoft.data[fy * pw + fx] - r);
            const int steep = std::abs(refSoft.data[i + 1] - refSoft.data[i - 1]) + std::abs(refSoft.data[i + pw] - refSoft.data[i - pw]);
            const float keep = 1.5f + 0.6f * noise[r] + 0.08f * steep;
            const float coarse = d <= keep ? 1.f : (d >= 2.f * keep ? 0.f : (2.f * keep - d) / keep);
            if (coarse == 0.f) continue;
            const float ex = qx * 2 + exact[2 * tile];
            const float ey = qy * 2 + exact[2 * tile + 1];
            const int ix = (int)std::floor(ex);
            const int iy = (int)std::floor(ey);
            if (ix < 0 || iy < 0 || ix >= w - 2 || iy >= h - 2) continue;
            const float ax = ex - ix, ay = ey - iy;
            const float there = (grey4(frame.data, ix, iy) * (1 - ax) + grey4(frame.data, ix + 1, iy) * ax) * (1 - ay) +
                                (grey4(frame.data, ix, iy + 1) * (1 - ax) + grey4(frame.data, ix + 1, iy + 1) * ax) * ay;
            const float here = grey4(ref.data, qx * 2, qy * 2);
            const float keepExact = 1.5f + 1.5f * noise[r];
            const float de = std::fabs(there - here);
            const float fine = de <= keepExact ? 1.f : (de >= 2.f * keepExact ? 0.f : (2.f * keepExact - de) / keepExact);
            out.data[i] = (uint8_t)(int)(255.f * coarse * fine);
        }
    });
}
