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
    jfloatArray jLight, jobjectArray jFrames, jobjectArray jTrust, jobjectArray jPlace,
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
    std::vector<std::vector<float>> greenWeights(levels);
    std::vector<std::vector<int>> place(n);
    for (int l = 0; l < levels; l++) greenWeights[l] = copyF((jfloatArray)env->GetObjectArrayElement(jGreenWeights, l));
    for (int k = 0; k < n; k++) {
        jintArray a = (jintArray)env->GetObjectArrayElement(jPlace, k);
        place[k].resize(env->GetArrayLength(a));
        env->GetIntArrayRegion(a, 0, place[k].size(), place[k].data());
    }
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
        // For each frame and each of the four pixel positions in a filter group: the taps that
        // count (weight from the table, colour, filter position, offset), for the current tile.
        // The same numbers the per-tap code would look up, so the result doesn't change.
        // Kept per colour, in the original order within each colour, so each colour's running
        // sum can live in a register and still adds up exactly as before.
        struct Tap { float wt; float black; int off, i, j; };
        // Divisions are slow on phone processors: the two that happen per sample come from
        // tables instead (exactly the same values).
        std::vector<float> greenOf(65536), trustOf(256);
        for (int v = 0; v < 65536; v++) greenOf[v] = v / 65535.f;
        for (int v = 0; v < 256; v++) trustOf[v] = v / 255.f;
        rows(h / 2, [&](int cy) {
            std::vector<Tap> taps(n * 4 * 3 * 9);
            std::vector<int> count(n * 4 * 3, 0);
            int tapsTile = -1;
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
                // Taps depend on the tile (placement) and on the level of detail: rebuilt when either changes.
                const int tapsKey = tile * 8 + level;
                if (tapsKey != tapsTile) {
                    tapsTile = tapsKey;
                    for (int k = 0; k < n; k++) {
                        const int* pl = place[k].data() + 3 * tile;
                        const int at = pl[2];
                        for (int p = 0; p < 4; p++) {
                            for (int c = 0; c < 3; c++) count[(k * 4 + p) * 3 + c] = 0;
                            for (int j = -1; j <= 1; j++) {
                                const int rowPos = (((p / 2) + pl[1] + j) & 1) * 2;
                                for (int i = -1; i <= 1; i++) {
                                    const int pos = rowPos + (((p % 2) + pl[0] + i) & 1);
                                    const int c = colourAt[pos];
                                    const int kk = (j + 1) * 3 + i + 1;
                                    const int slot = (k * 4 + p) * 3 + c;
                                    taps[slot * 9 + count[slot]++] = {c == 1 ? tight[at + kk] : wide[at + kk], black[pos], j * w + i, i, j};
                                }
                            }
                        }
                    }
                }
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
                        const int* pl = place[k].data() + 3 * tile;
                        const int ix = x + pl[0];
                        const int iy = y + pl[1];
                        if (ix < 1 || iy < 1 || ix >= w - 1 || iy >= h - 1) continue;
                        const int at = pl[2];
                        (void)at;
                        const uint16_t* fr = frames[k]->data;
                        const float tw = trustOf[t];
                        const uint16_t* centre = fr + iy * w + ix;
                        for (int c = 0; c < 3; c++) {
                            const int slot = (k * 4 + p) * 3 + c;
                            const Tap* list = &taps[slot * 9];
                            const int m = count[slot];
                            const float lg = lens[c] * gain[c];
                            float sc = sum[c], wc = weight[c];
                            for (int q = 0; q < m; q++) {
                                const Tap& tp = list[q];
                                const float wt = tw * tp.wt;
                                if (wt < 0.01f) continue;
                                float v = (centre[tp.off] - tp.black) * scale;
                                if (v < 0) v = 0;
                                v *= lg;
                                if (v > 1) v = 1;
                                if (c != 1) {
                                    const int gx = std::clamp(x + tp.i, 0, w - 1);
                                    const int gy = std::clamp(y + tp.j, 0, h - 1);
                                    v -= greenOf[green.data[gy * w + gx]];
                                }
                                sc += v * wt;
                                wc += wt;
                            }
                            sum[c] = sc;
                            weight[c] = wc;
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

// Stack.refine: each tile's shift to a fraction of a sensor pixel.
extern "C" JNIEXPORT void JNICALL
Java_com_anycam_app_core_Native_refine(
    JNIEnv* env, jclass, jint w, jint h, jint cols, jint rowsN, jint size, jintArray jDx, jintArray jDy, jfloatArray jCost,
    jshortArray jRef, jshortArray jFrame, jfloatArray jGreyOf, jfloat cap, jfloatArray jOut) {
    std::vector<int> dx(env->GetArrayLength(jDx)), dy(env->GetArrayLength(jDy));
    env->GetIntArrayRegion(jDx, 0, dx.size(), dx.data());
    env->GetIntArrayRegion(jDy, 0, dy.size(), dy.data());
    std::vector<float> cost(env->GetArrayLength(jCost)), greyOf(env->GetArrayLength(jGreyOf)), out(2 * dx.size());
    env->GetFloatArrayRegion(jCost, 0, cost.size(), cost.data());
    env->GetFloatArrayRegion(jGreyOf, 0, greyOf.size(), greyOf.data());
    const int top = (int)greyOf.size() - 1;
    {
        Pinned<uint16_t> ref(env, jRef), frame(env, jFrame);
        auto grey = [&](const uint16_t* f, int x, int y) {
            int o = y * w + x;
            int v = (f[o] + f[o + 1] + f[o + w] + f[o + w + 1]) >> 2;
            return greyOf[v > top ? top : v];
        };
        const float MAXF = 3.4028235e38f;
        rows(rowsN, [&](int r) {
            float grid[25];
            for (int c = 0; c < cols; c++) {
                const int tile = r * cols + c;
                const int bx = 2 * dx[tile], by = 2 * dy[tile];
                out[2 * tile] = (float)bx;
                out[2 * tile + 1] = (float)by;
                if (cost[tile] == MAXF) continue;
                const int x0 = c * size, y0 = r * size;
                const int x1 = std::min(w - 1, x0 + size), y1 = std::min(h - 1, y0 + size);
                float best = MAXF;
                int bi = 0, bj = 0;
                for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
                    double sum = 0;
                    int n = 0;
                    for (int y = y0; y < y1; y += 3) {
                        const int fy = y + by + j;
                        if (fy < 0 || fy >= h - 1) continue;
                        for (int x = x0; x < x1; x += 3) {
                            const int fx = x + bx + i;
                            if (fx < 0 || fx >= w - 1) continue;
                            const float d = grey(ref.data, x, y) - grey(frame.data, fx, fy);
                            sum += (d > cap || d < -cap) ? cap * cap : d * d;
                            n++;
                        }
                    }
                    const float cst = n < 64 ? MAXF : (float)(sum / n);
                    grid[(j + 2) * 5 + i + 2] = cst;
                    if (cst < best) { best = cst; bi = i; bj = j; }
                }
                if (best == MAXF) continue;
                auto between = [&](float lo, float hi) {
                    if (lo == MAXF || hi == MAXF) return 0.f;
                    const float curve = lo + hi - 2 * best;
                    return curve <= 1e-4f ? 0.f : clampf((lo - hi) / (2 * curve), -0.5f, 0.5f);
                };
                const int at = (bj + 2) * 5 + bi + 2;
                const float sx = std::abs(bi) < 2 ? between(grid[at - 1], grid[at + 1]) : 0.f;
                const float sy = std::abs(bj) < 2 ? between(grid[at - 5], grid[at + 5]) : 0.f;
                out[2 * tile] = bx + bi + sx;
                out[2 * tile + 1] = by + bj + sy;
            }
        });
    }
    env->SetFloatArrayRegion(jOut, 0, out.size(), out.data());
}

// Align.tiles: the search for each tile's shift (to the pixel, and the fraction from the cost curve).
extern "C" JNIEXPORT void JNICALL
Java_com_anycam_app_core_Native_tiles(
    JNIEnv* env, jclass, jint rw, jint rh, jbyteArray jRef, jint fw, jint fh, jbyteArray jFrame, jint gx, jint gy,
    jint tileSize, jint reach, jint cap, jintArray jDx, jintArray jDy, jfloatArray jCost, jfloatArray jSubX, jfloatArray jSubY) {
    const int cols = (rw + tileSize - 1) / tileSize, rowsN = (rh + tileSize - 1) / tileSize;
    const int tiles = cols * rowsN, span = 2 * reach + 1;
    std::vector<int> dx(tiles), dy(tiles);
    std::vector<float> cost(tiles), subX(tiles, 0.f), subY(tiles, 0.f);
    {
        Pinned<uint8_t> ref(env, jRef), frame(env, jFrame);
        const float MAXF = 3.4028235e38f;
        rows(rowsN, [&](int r) {
            std::vector<float> grid(span * span);
            for (int c = 0; c < cols; c++) {
                std::fill(grid.begin(), grid.end(), NAN);
                int bestOx = 0, bestOy = 0;
                const int x0 = c * tileSize, y0 = r * tileSize;
                const int x1 = std::min(rw, x0 + tileSize), y1 = std::min(rh, y0 + tileSize);
                int64_t best = INT64_MAX;
                int bx = gx, by = gy, bestN = 1;
                int64_t bestSad = 0;
                for (int oy = -reach; oy <= reach; oy++) for (int ox = -reach; ox <= reach; ox++) {
                    const int sx = gx + ox, sy = gy + oy;
                    int64_t sad = 0;
                    int n = 0;
                    for (int y = y0; y < y1; y += 2) {
                        const int fy = y + sy;
                        if (fy < 0 || fy >= fh) continue;
                        for (int x = x0; x < x1; x += 2) {
                            const int fx = x + sx;
                            if (fx < 0 || fx >= fw) continue;
                            int d = std::abs((int)ref.data[y * rw + x] - (int)frame.data[fy * fw + fx]);
                            sad += d > cap ? cap : d;
                            n++;
                        }
                    }
                    if (n < tileSize * tileSize / 16) continue;
                    grid[(oy + reach) * span + ox + reach] = (float)sad / n;
                    const int64_t score = sad * 4096 / n + (int64_t)(std::abs(ox) + std::abs(oy)) * 24;
                    if (score < best) { best = score; bx = sx; by = sy; bestSad = sad; bestN = n; bestOx = ox; bestOy = oy; }
                }
                const int i = r * cols + c;
                dx[i] = bx;
                dy[i] = by;
                if (best != INT64_MAX && std::abs(bestOx) < reach && std::abs(bestOy) < reach) {
                    const int at = (bestOy + reach) * span + bestOx + reach;
                    const float mid = grid[at];
                    auto part = [&](float lo, float hi) {
                        if (std::isnan(lo) || std::isnan(hi)) return 0.f;
                        const float curve = lo + hi - 2 * mid;
                        return curve <= 1e-4f ? 0.f : clampf((lo - hi) / (2 * curve), -0.5f, 0.5f);
                    };
                    subX[i] = part(grid[at - 1], grid[at + 1]);
                    subY[i] = part(grid[at - span], grid[at + span]);
                }
                cost[i] = best == INT64_MAX ? MAXF : (float)bestSad / bestN;
            }
        });
    }
    env->SetIntArrayRegion(jDx, 0, tiles, dx.data());
    env->SetIntArrayRegion(jDy, 0, tiles, dy.data());
    env->SetFloatArrayRegion(jCost, 0, tiles, cost.data());
    env->SetFloatArrayRegion(jSubX, 0, tiles, subX.data());
    env->SetFloatArrayRegion(jSubY, 0, tiles, subY.data());
}

// Stack.kt "prepare": how busy each filter group is, and the reference's green at every pixel.
extern "C" JNIEXPORT void JNICALL
Java_com_anycam_app_core_Native_prepare(
    JNIEnv* env, jclass, jint w, jint h, jint pw, jint ph, jint levels,
    jbyteArray jRefGroups, jbyteArray jRefSoft, jfloatArray jNoise, jfloatArray jLight,
    jintArray jColourAt, jbooleanArray jBesideIsRed, jbyteArray jBusy, jshortArray jGreen) {
    float noise[256];
    env->GetFloatArrayRegion(jNoise, 0, 256, noise);
    int colourAt[4];
    env->GetIntArrayRegion(jColourAt, 0, 4, colourAt);
    jboolean bir[4];
    env->GetBooleanArrayRegion(jBesideIsRed, 0, 4, bir);
    Pinned<uint8_t> groups(env, jRefGroups), soft(env, jRefSoft);
    Pinned<int8_t> busy(env, jBusy);
    Pinned<float> light(env, jLight);
    Pinned<uint16_t> green(env, jGreen);
    rows(ph, [&](int qy) {
        if (qy < 1 || qy >= ph - 1) return;
        for (int qx = 1; qx < pw - 1; qx++) {
            const int i = qy * pw + qx;
            const int r = soft.data[i];
            int lo = 255, hi = 0;
            for (int j = -1; j <= 1; j++) for (int k = -1; k <= 1; k++) {
                const int v = groups.data[i + j * pw + k];
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            const float level = (hi - lo - 2.f * noise[r]) / (0.5f * noise[r] + 2.f);
            busy.data[i] = (int8_t)std::clamp((int)level, 0, levels - 1);
        }
    });
    const int s = w + 4;
    rows(h, [&](int y) {
        float rgb[3];
        for (int x = 0; x < w; x++) {
            const int pos = (y & 1) * 2 + (x & 1);
            pixel(light.data, s, x, y, colourAt[pos], bir[pos], rgb);
            green.data[y * w + x] = (uint16_t)(int)(clampf(rgb[1], 0.f, 1.f) * 65535.f);
        }
    });
}

// Stack.kt: a byte plane averaged over each 3x3 neighbourhood (edges copied).
extern "C" JNIEXPORT void JNICALL
Java_com_anycam_app_core_Native_blur3(JNIEnv* env, jclass, jint w, jint h, jbyteArray jIn, jbyteArray jOut) {
    Pinned<uint8_t> in(env, jIn), out(env, jOut);
    std::copy(in.data, in.data + w * h, out.data);
    rows(h, [&](int y) {
        if (y < 1 || y >= h - 1) return;
        for (int x = 1; x < w - 1; x++) {
            int sum = 0;
            const int i = y * w + x;
            for (int j = -1; j <= 1; j++) for (int k = -1; k <= 1; k++) sum += in.data[i + j * w + k];
            out.data[i] = (uint8_t)((sum + 4) / 9);
        }
    });
}

// Stack.kt: each group's trust becomes the lower of itself and its neighbourhood's average.
extern "C" JNIEXPORT void JNICALL
Java_com_anycam_app_core_Native_calm(JNIEnv* env, jclass, jint pw, jint ph, jbyteArray jIn, jbyteArray jOut) {
    Pinned<uint8_t> in(env, jIn), out(env, jOut);
    rows(ph, [&](int qy) {
        if (qy < 1 || qy >= ph - 1) return;
        for (int qx = 1; qx < pw - 1; qx++) {
            const int i = qy * pw + qx;
            int sum = 0;
            for (int j = -1; j <= 1; j++) for (int m = -1; m <= 1; m++) sum += in.data[i + j * pw + m];
            out.data[i] = (uint8_t)std::min((int)in.data[i], sum / 9);
        }
    });
}
