// The finishing steps of a sensor photo (Detail.kt: calmColour, clarity, sharpen), compiled.
// Same integer and float arithmetic in the same order as the Kotlin, which stays the reference.
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <thread>
#include <vector>

namespace {

template <typename F>
void rowsF(int count, F body) {
    int threads = (int)std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    std::atomic<int> next(0);
    std::vector<std::thread> pool;
    for (int t = 0; t < std::min(threads, count); t++)
        pool.emplace_back([&]() { for (int r; (r = next.fetch_add(1)) < count;) body(r); });
    for (auto& th : pool) th.join();
}

void soften(const uint8_t* src, uint8_t* out, int w, int h) {
    if (w < 3 || h < 3) { std::copy(src, src + w * h, out); return; }
    std::vector<int16_t> across(w * h);
    rowsF(h, [&](int y) {
        const int o = y * w;
        for (int x = 0; x < w; x++) {
            int l = src[o + (x > 0 ? x - 1 : x)], r = src[o + (x < w - 1 ? x + 1 : x)];
            across[o + x] = (int16_t)(l + 2 * src[o + x] + r);
        }
    });
    rowsF(h, [&](int y) {
        const int o = y * w, up = y > 0 ? o - w : o, down = y < h - 1 ? o + w : o;
        for (int x = 0; x < w; x++) out[o + x] = (uint8_t)((across[up + x] + 2 * across[o + x] + across[down + x] + 8) / 16);
    });
}

void boxRows(const int* src, int* dst, int w, int h, int r) {
    rowsF(h, [&](int y) {
        const int o = y * w, n = 2 * r + 1;
        int sum = 0;
        for (int i = -r; i <= r; i++) sum += src[o + std::clamp(i, 0, w - 1)];
        for (int x = 0; x < w; x++) {
            dst[o + x] = sum / n;
            sum += src[o + std::min(x + r + 1, w - 1)] - src[o + std::max(x - r, 0)];
        }
    });
}

void boxCols(const int* src, int* dst, int w, int h, int r) {
    const int n = 2 * r + 1;
    rowsF((w + 63) / 64, [&](int band) {
        const int x0 = band * 64, x1 = std::min(w, x0 + 64);
        std::vector<int> sum(x1 - x0, 0);
        for (int x = x0; x < x1; x++) for (int i = -r; i <= r; i++) sum[x - x0] += src[std::clamp(i, 0, h - 1) * w + x];
        for (int y = 0; y < h; y++) {
            const int add = std::min(y + r + 1, h - 1) * w, sub = std::max(y - r, 0) * w;
            for (int x = x0; x < x1; x++) {
                dst[y * w + x] = sum[x - x0] / n;
                sum[x - x0] += src[add + x] - src[sub + x];
            }
        }
    });
}

void clarity(const uint8_t* src, uint8_t* out, int w, int h, float amount) {
    const int radius = std::max(std::max(w, h) / 100, 2);
    if (w <= 2 * radius || h <= 2 * radius || amount <= 0.f) { std::copy(src, src + w * h, out); return; }
    std::vector<int> a(w * h), b(w * h);
    for (int i = 0; i < w * h; i++) a[i] = src[i];
    for (int k = 0; k < 3; k++) {
        boxRows(a.data(), b.data(), w, h, radius);
        boxCols(b.data(), a.data(), w, h, radius);
    }
    rowsF(h, [&](int y) {
        const int o = y * w;
        for (int x = 0; x < w; x++) {
            const int v = src[o + x];
            const float mid = 4.f * v * (255 - v) / (255.f * 255.f);
            out[o + x] = (uint8_t)std::clamp((int)(v + amount * mid * (float)(v - a[o + x]) + 0.5f), 0, 255);
        }
    });
}

void sharpen(const uint8_t* src, uint8_t* out, int w, int h, float noiseLeft, float amount) {
    if (w < 5 || h < 5 || amount <= 0.f) { std::copy(src, src + w * h, out); return; }
    const float floorV = std::clamp(noiseLeft * 1.5f, 1.f, 12.f);
    std::vector<int16_t> across(w * h);
    rowsF(h, [&](int y) {
        const int o = y * w;
        for (int x = 0; x < w; x++) {
            int l = src[o + (x > 0 ? x - 1 : x)], c = src[o + x], r = src[o + (x < w - 1 ? x + 1 : x)];
            across[o + x] = (int16_t)(l + 2 * c + r);
        }
    });
    rowsF(h, [&](int y) {
        const int o = y * w, up = y > 0 ? o - w : o, down = y < h - 1 ? o + w : o;
        for (int x = 0; x < w; x++) {
            const float soft = (across[up + x] + 2 * across[o + x] + across[down + x]) / 16.f;
            const int v = src[o + x];
            float d = v - soft;
            d = d > floorV ? d - floorV : (d < -floorV ? d + floorV : 0.f);
            if (d == 0.f) { out[o + x] = src[o + x]; continue; }
            int lo = v, hi = v;
            const int x0 = x > 0 ? x - 1 : x, x1 = x < w - 1 ? x + 1 : x;
            for (int row : {up, o, down})
                for (int xx = x0; xx <= x1; xx++) { int n = src[row + xx]; if (n < lo) lo = n; if (n > hi) hi = n; }
            const int room = (hi - lo) / 6;
            const int sharp = (int)(v + amount * d + 0.5f);
            out[o + x] = (uint8_t)std::clamp(sharp, std::max(0, lo - room), std::min(255, hi + room));
        }
    });
}

}  // namespace

// calmColour, then clarity, then sharpen (as PhotoMaker does for sensor photos).
extern "C" JNIEXPORT void JNICALL
Java_com_anycam_app_core_Native_finish(JNIEnv* env, jclass, jint w, jint h, jbyteArray jY, jbyteArray jU, jbyteArray jV,
    jfloat clarityAmount, jfloat noiseLeft, jfloat sharpenAmount, jbyteArray jOutY, jbyteArray jOutU, jbyteArray jOutV) {
    const int cw = w / 2, ch = h / 2;
    std::vector<uint8_t> y(w * h), u(cw * ch), v(cw * ch), outU(cw * ch), outV(cw * ch), mid(w * h), outY(w * h);
    env->GetByteArrayRegion(jY, 0, w * h, (jbyte*)y.data());
    env->GetByteArrayRegion(jU, 0, cw * ch, (jbyte*)u.data());
    env->GetByteArrayRegion(jV, 0, cw * ch, (jbyte*)v.data());
    soften(u.data(), outU.data(), cw, ch);
    soften(v.data(), outV.data(), cw, ch);
    clarity(y.data(), mid.data(), w, h, clarityAmount);
    sharpen(mid.data(), outY.data(), w, h, noiseLeft, sharpenAmount);
    env->SetByteArrayRegion(jOutY, 0, w * h, (const jbyte*)outY.data());
    env->SetByteArrayRegion(jOutU, 0, cw * ch, (const jbyte*)outU.data());
    env->SetByteArrayRegion(jOutV, 0, cw * ch, (const jbyte*)outV.data());
}
