#include <jni.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <deque>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr int kMaxCores = 32;
constexpr int kCatCount = 7; // 0:Photo, 1:Video, 2:Audio, 3:Doc, 4:APK, 5:Cache, 6:Other
constexpr size_t kTopHeavyLimit = 25;
constexpr size_t kDupMinSize = 65536; // 64 KiB
constexpr size_t kMaxDupCandidates = 120000;

inline uint64_t monotonic_ns() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

// ---------------------------------------------------------------------------
// Custom lightweight barrier (no C++ exceptions / RTTI)
// ---------------------------------------------------------------------------
struct SyncBarrier {
    pthread_mutex_t mtx;
    pthread_cond_t cv;
    int threshold;
    int count;
    int generation;

    explicit SyncBarrier(int n) : threshold(n), count(n), generation(0) {
        pthread_mutex_init(&mtx, nullptr);
        pthread_cond_init(&cv, nullptr);
    }

    ~SyncBarrier() {
        pthread_mutex_destroy(&mtx);
        pthread_cond_destroy(&cv);
    }

    void wait() {
        pthread_mutex_lock(&mtx);
        int gen = generation;
        if (--count == 0) {
            generation++;
            count = threshold;
            pthread_cond_broadcast(&cv);
        } else {
            while (gen == generation) {
                pthread_cond_wait(&cv, &mtx);
            }
        }
        pthread_mutex_unlock(&mtx);
    }
};

// ---------------------------------------------------------------------------
// Static zero-allocation state for /proc/stat delta calculation
// ---------------------------------------------------------------------------
pthread_mutex_t g_cpu_mtx = PTHREAD_MUTEX_INITIALIZER;
uint64_t g_prev_total[kMaxCores + 1] = {0};
uint64_t g_prev_idle[kMaxCores + 1] = {0};
bool g_has_prev[kMaxCores + 1] = {false};

int read_int_sysfs(const char* path, int fallback = 0) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fallback;
    char buf[32];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return fallback;
    buf[n] = '\0';
    return atoi(buf);
}

bool read_str_sysfs(const char* path, char* out, size_t out_sz) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n = read(fd, out, out_sz - 1);
    close(fd);
    if (n <= 0) return false;
    out[n] = '\0';
    return true;
}

float compute_cpu_delta(int slot, uint64_t total, uint64_t idle) {
    if (!g_has_prev[slot]) {
        g_prev_total[slot] = total;
        g_prev_idle[slot] = idle;
        g_has_prev[slot] = true;
        return 0.0f;
    }
    uint64_t dt = (total >= g_prev_total[slot]) ? (total - g_prev_total[slot]) : 0ULL;
    uint64_t di = (idle >= g_prev_idle[slot]) ? (idle - g_prev_idle[slot]) : 0ULL;
    g_prev_total[slot] = total;
    g_prev_idle[slot] = idle;
    if (dt == 0) return 0.0f;
    float busy = static_cast<float>(dt > di ? (dt - di) : 0ULL);
    float pct = (busy * 100.0f) / static_cast<float>(dt);
    if (pct < 0.0f) pct = 0.0f;
    if (pct > 100.0f) pct = 100.0f;
    return pct;
}

bool equals_ci(const char* a, const char* b) {
    while (*a && *b) {
        if (std::tolower(static_cast<unsigned char>(*a)) !=
            std::tolower(static_cast<unsigned char>(*b))) {
            return false;
        }
        ++a;
        ++b;
    }
    return *a == '\0' && *b == '\0';
}

bool contains_ci(const std::string& hay, const char* needle) {
    size_t nlen = strlen(needle);
    if (hay.size() < nlen) return false;
    for (size_t i = 0; i <= hay.size() - nlen; ++i) {
        bool match = true;
        for (size_t j = 0; j < nlen; ++j) {
            if (std::tolower(static_cast<unsigned char>(hay[i + j])) !=
                std::tolower(static_cast<unsigned char>(needle[j]))) {
                match = false;
                break;
            }
        }
        if (match) return true;
    }
    return false;
}

int classify_file(const char* name, const std::string& full_path) {
    if (contains_ci(full_path, "/cache/") ||
        contains_ci(full_path, "/.thumbnails/") ||
        contains_ci(full_path, "/temp/")) {
        return 5;
    }

    const char* dot = strrchr(name, '.');
    if (!dot || dot[1] == '\0') {
        return 6;
    }
    const char* ext = dot + 1;

    static const char* kPhoto[] = {"jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng", "raw", "svg"};
    for (const char* e : kPhoto) if (equals_ci(ext, e)) return 0;

    static const char* kVideo[] = {"mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "m4v", "ts"};
    for (const char* e : kVideo) if (equals_ci(ext, e)) return 1;

    static const char* kAudio[] = {"mp3", "aac", "flac", "wav", "ogg", "opus", "m4a", "wma", "amr"};
    for (const char* e : kAudio) if (equals_ci(ext, e)) return 2;

    static const char* kDoc[] = {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub", "json", "xml", "rtf"};
    for (const char* e : kDoc) if (equals_ci(ext, e)) return 3;

    static const char* kApk[] = {"apk", "apks", "xapk", "aab"};
    for (const char* e : kApk) if (equals_ci(ext, e)) return 4;

    static const char* kCache[] = {"tmp", "temp", "log", "bak", "cache", "exo", "dmp", "thumb", "part"};
    for (const char* e : kCache) if (equals_ci(ext, e)) return 5;

    return 6;
}

// ---------------------------------------------------------------------------
// Multi-Threaded Storage Scanner + Folder Buckets + Duplicate Hasher
// ---------------------------------------------------------------------------
struct FileRecord {
    uint64_t size;
    std::string path;
    std::string name;
};

struct DirBucketStat {
    uint64_t bytes = 0;
    uint64_t files = 0;
};

struct ScanWorkQueue {
    pthread_mutex_t mtx;
    pthread_cond_t cv;
    std::deque<std::string> dirs;
    int active;
    bool closed;
    std::string root;

    explicit ScanWorkQueue(const std::string& r) : active(0), closed(false), root(r) {
        pthread_mutex_init(&mtx, nullptr);
        pthread_cond_init(&cv, nullptr);
        dirs.push_back(r);
    }

    ~ScanWorkQueue() {
        pthread_mutex_destroy(&mtx);
        pthread_cond_destroy(&cv);
    }
};

struct WorkerAcc {
    uint64_t files = 0;
    uint64_t dirs = 0;
    uint64_t bytes = 0;
    uint64_t errors = 0;
    uint64_t cat_count[kCatCount] = {0};
    uint64_t cat_bytes[kCatCount] = {0};
    std::vector<FileRecord> top_files;
    std::vector<std::pair<uint64_t, std::string>> dup_candidates;
    std::unordered_map<std::string, DirBucketStat> folder_buckets;
};

struct ScanWorkerArg {
    ScanWorkQueue* queue;
    WorkerAcc acc;
};

void push_top_file(std::vector<FileRecord>& top, uint64_t size, const std::string& path, const char* name) {
    if (top.size() < kTopHeavyLimit) {
        top.push_back(FileRecord{size, path, name});
        if (top.size() == kTopHeavyLimit) {
            std::make_heap(top.begin(), top.end(), [](const FileRecord& a, const FileRecord& b) {
                return a.size > b.size;
            });
        }
    } else if (size > top.front().size) {
        std::pop_heap(top.begin(), top.end(), [](const FileRecord& a, const FileRecord& b) {
            return a.size > b.size;
        });
        top.back() = FileRecord{size, path, name};
        std::push_heap(top.begin(), top.end(), [](const FileRecord& a, const FileRecord& b) {
            return a.size > b.size;
        });
    }
}

std::string top_bucket_name(const std::string& root, const std::string& dir_path) {
    if (dir_path.size() <= root.size()) return "/";
    size_t start = root.size();
    while (start < dir_path.size() && dir_path[start] == '/') start++;
    if (start >= dir_path.size()) return "/";
    size_t slash = dir_path.find('/', start);
    if (slash == std::string::npos) return dir_path.substr(start);
    return dir_path.substr(start, slash - start);
}

void* scan_worker_fn(void* raw) {
    auto* arg = static_cast<ScanWorkerArg*>(raw);
    ScanWorkQueue* q = arg->queue;
    WorkerAcc& acc = arg->acc;

    std::vector<std::string> local_dirs;
    local_dirs.reserve(64);

    while (true) {
        std::string cur;
        pthread_mutex_lock(&q->mtx);
        while (q->dirs.empty() && q->active > 0 && !q->closed) {
            pthread_cond_wait(&q->cv, &q->mtx);
        }
        if (q->dirs.empty() && q->active == 0) {
            q->closed = true;
            pthread_cond_broadcast(&q->cv);
            pthread_mutex_unlock(&q->mtx);
            break;
        }
        if (q->closed && q->dirs.empty()) {
            pthread_mutex_unlock(&q->mtx);
            break;
        }
        cur = std::move(q->dirs.back());
        q->dirs.pop_back();
        q->active++;
        pthread_mutex_unlock(&q->mtx);

        DIR* dir = opendir(cur.c_str());
        if (!dir) {
            acc.errors++;
        } else {
            acc.dirs++;
            local_dirs.clear();
            std::string bucket = top_bucket_name(q->root, cur);
            auto& bstat = acc.folder_buckets[bucket];

            struct dirent* ent = nullptr;
            while ((ent = readdir(dir)) != nullptr) {
                const char* name = ent->d_name;
                if (name[0] == '.' && (name[1] == '\0' || (name[1] == '.' && name[2] == '\0'))) {
                    continue;
                }
                std::string full = cur;
                if (full.back() != '/') full.push_back('/');
                full.append(name);

                if (full == "/proc" || full == "/sys" || full == "/dev") {
                    continue;
                }

                struct stat st{};
                if (lstat(full.c_str(), &st) != 0) {
                    acc.errors++;
                    continue;
                }
                if (S_ISLNK(st.st_mode)) {
                    continue;
                }
                if (S_ISDIR(st.st_mode)) {
                    local_dirs.push_back(std::move(full));
                } else if (S_ISREG(st.st_mode)) {
                    uint64_t sz = st.st_size > 0 ? static_cast<uint64_t>(st.st_size) : 0ULL;
                    acc.files++;
                    acc.bytes += sz;
                    bstat.bytes += sz;
                    bstat.files++;

                    int cat = classify_file(name, full);
                    acc.cat_count[cat]++;
                    acc.cat_bytes[cat] += sz;

                    if (sz >= 102400ULL) {
                        push_top_file(acc.top_files, sz, full, name);
                    }
                    if (sz >= kDupMinSize && acc.dup_candidates.size() < 40000) {
                        acc.dup_candidates.emplace_back(sz, std::move(full));
                    }
                }
            }
            closedir(dir);

            if (!local_dirs.empty()) {
                pthread_mutex_lock(&q->mtx);
                for (auto& d : local_dirs) {
                    q->dirs.push_back(std::move(d));
                }
                pthread_cond_broadcast(&q->cv);
                pthread_mutex_unlock(&q->mtx);
            }
        }

        pthread_mutex_lock(&q->mtx);
        q->active--;
        if (q->dirs.empty() && q->active == 0) {
            q->closed = true;
            pthread_cond_broadcast(&q->cv);
        }
        pthread_mutex_unlock(&q->mtx);
    }
    return nullptr;
}

uint64_t fnv1a_sample_hash(const std::string& path, uint64_t size) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;

    uint64_t h = 14695981039346656037ULL;
    auto feed = [&h](const uint8_t* data, ssize_t len) {
        for (ssize_t i = 0; i < len; ++i) {
            h ^= data[i];
            h *= 1099511628211ULL;
        }
    };

    feed(reinterpret_cast<const uint8_t*>(&size), sizeof(size));

    uint8_t buf[4096];
    ssize_t n = pread(fd, buf, sizeof(buf), 0);
    if (n > 0) feed(buf, n);

    if (size > sizeof(buf) * 2) {
        off_t mid = static_cast<off_t>(size / 2 - sizeof(buf) / 2);
        n = pread(fd, buf, sizeof(buf), mid);
        if (n > 0) feed(buf, n);
    }
    if (size > sizeof(buf)) {
        off_t tail = static_cast<off_t>(size - sizeof(buf));
        n = pread(fd, buf, sizeof(buf), tail);
        if (n > 0) feed(buf, n);
    }

    close(fd);
    return h == 0 ? 1ULL : h;
}

void sanitize_field(std::string& s) {
    for (char& ch : s) {
        if (ch == '\t' || ch == '\n' || ch == '\r') ch = ' ';
    }
}

// ---------------------------------------------------------------------------
// StellarHub Benchmark Kernels (Int ALU, NEON Vector, SHA-256, LZ, RAM BW)
// ---------------------------------------------------------------------------
uint64_t run_int_kernel(uint64_t iters) {
    uint64_t a = 0x9e3779b97f4a7c15ULL;
    uint64_t b = 0xbf58476d1ce4e5b9ULL;
    uint64_t c = 0x94d049bb133111ebULL;
    uint64_t d = 0x243f6a8885a308d3ULL;

    for (uint64_t i = 0; i < iters; ++i) {
        a ^= b + 0x9e3779b97f4a7c15ULL + (a << 6) + (a >> 2);
        b = ((b << 13) | (b >> 51)) * 0xff51afd7ed558ccdULL;
        c += (a ^ b) + (d >> 7);
        d = ((d << 29) | (d >> 35)) ^ (c + i);
    }
    return a ^ b ^ c ^ d;
}

float run_vec_kernel(uint64_t iters) {
    alignas(16) float v0[16];
    alignas(16) float v1[16];
    alignas(16) float v2[16];
    for (int j = 0; j < 16; ++j) {
        v0[j] = 1.0001f + j * 0.013f;
        v1[j] = 0.9997f - j * 0.007f;
        v2[j] = 0.125f + j * 0.031f;
    }

    for (uint64_t i = 0; i < iters; ++i) {
        for (int j = 0; j < 16; ++j) {
            v0[j] = v0[j] * v1[j] + v2[j];
            v1[j] = v1[j] * 0.99999f + 0.00001f;
        }
        if ((i & 255ULL) == 0ULL) {
            for (int j = 0; j < 16; ++j) {
                v0[j] *= 0.25f;
            }
        }
    }
    float sum = 0.0f;
    for (int j = 0; j < 16; ++j) sum += v0[j];
    return sum;
}

inline uint32_t rotr32(uint32_t x, uint32_t n) {
    return (x >> n) | (x << (32U - n));
}

void sha256_compress_block(uint32_t state[8], const uint8_t block[64]) {
    static const uint32_t k[64] = {
        0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U, 0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
        0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U, 0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
        0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU, 0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
        0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U, 0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
        0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U, 0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
        0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U, 0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
        0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U, 0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
        0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U, 0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U
    };
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (static_cast<uint32_t>(block[i * 4]) << 24) |
               (static_cast<uint32_t>(block[i * 4 + 1]) << 16) |
               (static_cast<uint32_t>(block[i * 4 + 2]) << 8) |
               static_cast<uint32_t>(block[i * 4 + 3]);
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
    uint32_t e = state[4], f = state[5], g = state[6], h = state[7];
    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + S1 + ch + k[i] + w[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = S0 + maj;
        h = g; g = f; f = e; e = d + temp1;
        d = c; c = b; b = a; a = temp1 + temp2;
    }
    state[0] += a; state[1] += b; state[2] += c; state[3] += d;
    state[4] += e; state[5] += f; state[6] += g; state[7] += h;
}

uint64_t run_sha256_buffer(const uint8_t* data, size_t len) {
    uint32_t st[8] = {
        0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
        0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U
    };
    for (size_t off = 0; off + 64 <= len; off += 64) {
        sha256_compress_block(st, data + off);
    }
    return static_cast<uint64_t>(st[0]) ^ static_cast<uint64_t>(st[7]);
}

size_t run_lz_compress(const uint8_t* src, size_t len, uint8_t* dst, uint32_t table[4096]) {
    for (int i = 0; i < 4096; ++i) table[i] = 0xFFFFFFFFU;
    size_t i = 0, out_len = 0, lit = 0;
    while (i + 4 <= len) {
        uint32_t v = static_cast<uint32_t>(src[i]) |
                     (static_cast<uint32_t>(src[i + 1]) << 8) |
                     (static_cast<uint32_t>(src[i + 2]) << 16) |
                     (static_cast<uint32_t>(src[i + 3]) << 24);
        size_t h = ((v * 2654435761U) >> 20) & 4095U;
        uint32_t prev = table[h];
        table[h] = static_cast<uint32_t>(i);
        if (prev < i && (i - prev) <= 65535U && memcmp(src + prev, src + i, 4) == 0) {
            size_t mlen = 4;
            while (i + mlen < len && mlen < 131 && src[prev + mlen] == src[i + mlen]) mlen++;
            if (i > lit) {
                size_t chunk = i - lit;
                out_len += chunk + 2;
            }
            dst[out_len++ % len] = static_cast<uint8_t>(0x80U | ((mlen - 4) & 0x7FU));
            i += mlen;
            lit = i;
        } else {
            ++i;
        }
    }
    return out_len + (len - lit);
}

enum class BenchMode {
    INT_ALU = 0,
    VEC_FMA = 1,
    COMBINED = 2,
    SHA256 = 3,
    LZ_COMP = 4,
    RAM_READ = 5,
    RAM_WRITE = 6,
    RAM_COPY = 7
};

struct BenchWorkerArg {
    SyncBarrier* start_barrier;
    SyncBarrier* end_barrier;
    uint64_t duration_ns;
    BenchMode mode;
    uint8_t* buf_a;
    uint8_t* buf_b;
    size_t slice_bytes;
    uint64_t work_done; // ops or bytes
    volatile double sink;
};

void* bench_worker_fn(void* raw) {
    auto* arg = static_cast<BenchWorkerArg*>(raw);
    arg->start_barrier->wait();

    uint64_t t0 = monotonic_ns();
    uint64_t deadline = t0 + arg->duration_ns;
    uint64_t work = 0;
    uint64_t isink = 0;
    double fsink = 0.0;

    if (arg->mode == BenchMode::SHA256) {
        constexpr size_t kShaChunk = 262144; // 256 KiB
        auto* payload = static_cast<uint8_t*>(malloc(kShaChunk));
        if (payload) {
            for (size_t i = 0; i < kShaChunk; ++i) payload[i] = static_cast<uint8_t>((i * 73 + 19) & 0xFF);
            while (monotonic_ns() < deadline) {
                isink ^= run_sha256_buffer(payload, kShaChunk);
                work += kShaChunk;
            }
            free(payload);
        }
    } else if (arg->mode == BenchMode::LZ_COMP) {
        constexpr size_t kLzChunk = 131072; // 128 KiB
        auto* src = static_cast<uint8_t*>(malloc(kLzChunk));
        auto* dst = static_cast<uint8_t*>(malloc(kLzChunk));
        uint32_t table[4096];
        if (src && dst) {
            const char* pat = "NovaDeck-StellarHub-Compression-Benchmark-2026-0123456789ABCDEF-";
            size_t plen = strlen(pat);
            uint64_t seed = 0x9e3779b97f4a7c15ULL;
            for (size_t i = 0; i < kLzChunk; ++i) {
                if ((i & 63U) == 0U) seed = seed * 6364136223846793005ULL + 1ULL;
                src[i] = static_cast<uint8_t>(pat[(i + (seed >> 24)) % plen] ^ ((seed >> 8) & 0x0FU));
            }
            while (monotonic_ns() < deadline) {
                work += kLzChunk;
                isink ^= run_lz_compress(src, kLzChunk, dst, table);
            }
        }
        free(src);
        free(dst);
    } else if (arg->mode == BenchMode::RAM_READ) {
        const auto* p = reinterpret_cast<const uint64_t*>(arg->buf_a);
        size_t n = arg->slice_bytes / sizeof(uint64_t);
        while (monotonic_ns() < deadline) {
            uint64_t acc = 0;
            for (size_t i = 0; i + 4 <= n; i += 4) {
                acc += p[i] + p[i + 1] + p[i + 2] + p[i + 3];
            }
            isink ^= acc;
            work += arg->slice_bytes;
        }
    } else if (arg->mode == BenchMode::RAM_WRITE) {
        auto* p = reinterpret_cast<volatile uint64_t*>(arg->buf_b);
        size_t n = arg->slice_bytes / sizeof(uint64_t);
        uint64_t val = 0xdeadbeefcafebabeULL;
        while (monotonic_ns() < deadline) {
            for (size_t i = 0; i + 4 <= n; i += 4) {
                p[i] = val;
                p[i + 1] = val;
                p[i + 2] = val;
                p[i + 3] = val;
            }
            val++;
            work += arg->slice_bytes;
        }
    } else if (arg->mode == BenchMode::RAM_COPY) {
        while (monotonic_ns() < deadline) {
            memcpy(arg->buf_b, arg->buf_a, arg->slice_bytes);
            isink ^= arg->buf_b[0];
            work += arg->slice_bytes;
        }
    } else {
        while (monotonic_ns() < deadline) {
            if (arg->mode == BenchMode::INT_ALU) {
                isink ^= run_int_kernel(20000ULL);
                work += 20000ULL * 16ULL;
            } else if (arg->mode == BenchMode::VEC_FMA) {
                fsink += static_cast<double>(run_vec_kernel(4000ULL));
                work += 4000ULL * 32ULL;
            } else {
                isink ^= run_int_kernel(10000ULL);
                fsink += static_cast<double>(run_vec_kernel(2000ULL));
                work += 10000ULL * 16ULL + 2000ULL * 32ULL;
            }
        }
    }

    arg->work_done = work;
    arg->sink = static_cast<double>(isink) + fsink;
    arg->end_barrier->wait();
    return nullptr;
}

double run_parallel_bench(int threads, uint64_t duration_ns, BenchMode mode,
                          uint8_t* shared_a = nullptr, uint8_t* shared_b = nullptr, size_t total_ram_bytes = 0) {
    SyncBarrier start_bar(threads + 1);
    SyncBarrier end_bar(threads + 1);

    std::vector<pthread_t> tids(threads);
    std::vector<BenchWorkerArg> args(threads);
    size_t per_thread = (threads > 0 && total_ram_bytes > 0) ? (total_ram_bytes / static_cast<size_t>(threads)) : 0;

    for (int i = 0; i < threads; ++i) {
        args[i] = BenchWorkerArg{
            &start_bar, &end_bar, duration_ns, mode,
            shared_a ? (shared_a + static_cast<size_t>(i) * per_thread) : nullptr,
            shared_b ? (shared_b + static_cast<size_t>(i) * per_thread) : nullptr,
            per_thread, 0, 0.0
        };
        pthread_create(&tids[i], nullptr, bench_worker_fn, &args[i]);
    }

    start_bar.wait();
    uint64_t t0 = monotonic_ns();
    end_bar.wait();
    uint64_t elapsed_ns = monotonic_ns() - t0;

    for (int i = 0; i < threads; ++i) {
        pthread_join(tids[i], nullptr);
    }

    uint64_t total_work = 0;
    for (int i = 0; i < threads; ++i) {
        total_work += args[i].work_done;
    }
    double secs = elapsed_ns > 0 ? static_cast<double>(elapsed_ns) / 1e9 : 0.001;
    if (mode == BenchMode::SHA256 || mode == BenchMode::LZ_COMP) {
        return (static_cast<double>(total_work) / (1048576.0 * secs)); // MB/s
    }
    if (mode == BenchMode::RAM_READ || mode == BenchMode::RAM_WRITE || mode == BenchMode::RAM_COPY) {
        return (static_cast<double>(total_work) / (1073741824.0 * secs)); // GB/s
    }
    return (static_cast<double>(total_work) / secs) / 1e9; // GOPS / GFLOPS
}

double measure_dram_latency_ns() {
    constexpr size_t kElems = 1048576; // 4 MiB ring
    auto* ring = static_cast<uint32_t*>(malloc(kElems * sizeof(uint32_t)));
    if (!ring) return 0.0;

    for (size_t i = 0; i < kElems; ++i) {
        ring[i] = static_cast<uint32_t>(i);
    }
    uint64_t seed = 0x123456789abcdef1ULL;
    for (size_t i = kElems - 1; i > 0; --i) {
        seed = seed * 6364136223846793005ULL + 1442695040888963407ULL;
        size_t j = static_cast<size_t>((seed >> 32) % i);
        uint32_t tmp = ring[i];
        ring[i] = ring[j];
        ring[j] = tmp;
    }

    constexpr uint64_t kHops = 2000000ULL;
    uint32_t idx = 0;
    uint64_t t0 = monotonic_ns();
    for (uint64_t k = 0; k < kHops; ++k) {
        idx = ring[idx];
    }
    uint64_t elapsed = monotonic_ns() - t0;
    volatile uint32_t sink = idx;
    (void)sink;
    free(ring);
    return static_cast<double>(elapsed) / static_cast<double>(kHops);
}

// ---------------------------------------------------------------------------
// Socket /proc/net parser helpers
// ---------------------------------------------------------------------------
std::string decode_hex_ipv4(const char* hex) {
    unsigned int val = 0;
    if (sscanf(hex, "%8x", &val) != 1) return "0.0.0.0";
    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u.%u",
             val & 0xFFU, (val >> 8) & 0xFFU, (val >> 16) & 0xFFU, (val >> 24) & 0xFFU);
    return std::string(buf);
}

const char* tcp_state_name(int st) {
    switch (st) {
        case 1: return "ESTABLISHED";
        case 2: return "SYN_SENT";
        case 3: return "SYN_RECV";
        case 4: return "FIN_WAIT1";
        case 5: return "FIN_WAIT2";
        case 6: return "TIME_WAIT";
        case 7: return "CLOSE";
        case 8: return "CLOSE_WAIT";
        case 9: return "LAST_ACK";
        case 10: return "LISTEN";
        case 11: return "CLOSING";
        default: return "OTHER";
    }
}

bool parse_proc_net(const char* path, const char* proto, bool is_v6, std::string& out, int& count, int max_count) {
    FILE* fp = fopen(path, "re");
    if (!fp) return false;
    char line[512];
    fgets(line, sizeof(line), fp); // skip header
    while (fgets(line, sizeof(line), fp) && count < max_count) {
        char laddr[64] = {0}, raddr[64] = {0};
        unsigned int lport = 0, rport = 0, state = 0, uid = 0;
        int matched = sscanf(line, " %*d: %63[^:]:%x %63[^:]:%x %x %*s %*s %*s %u",
                             laddr, &lport, raddr, &rport, &state, &uid);
        if (matched >= 5) {
            std::string lip = is_v6 ? "[v6]" : decode_hex_ipv4(laddr);
            std::string rip = is_v6 ? "[v6]" : decode_hex_ipv4(raddr);
            char row[256];
            snprintf(row, sizeof(row), "%s\t%s:%u\t%s:%u\t%s\t%u\n",
                     proto, lip.c_str(), lport, rip.c_str(), rport,
                     (strncmp(proto, "UDP", 3) == 0 && state == 7) ? "BOUND" : tcp_state_name(static_cast<int>(state)),
                     uid);
            out.append(row);
            count++;
        }
    }
    fclose(fp);
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeGetCpuCoreCount(JNIEnv*, jobject) {
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n < 1) n = 1;
    if (n > kMaxCores) n = kMaxCores;
    return static_cast<jint>(n);
}

JNIEXPORT jint JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeReadCpuMetrics(
    JNIEnv* env, jobject, jfloatArray outMetrics) {
    if (!outMetrics) return 0;
    jsize len = env->GetArrayLength(outMetrics);
    long cores = sysconf(_SC_NPROCESSORS_CONF);
    if (cores < 1) cores = 1;
    if (cores > kMaxCores) cores = kMaxCores;

    jsize needed = 2 + static_cast<jsize>(cores) * 3;
    if (len < needed) return 0;

    float buf[2 + kMaxCores * 3];
    for (jsize i = 0; i < needed; ++i) buf[i] = 0.0f;
    buf[0] = -1.0f;
    buf[1] = 0.0f;
    for (long c = 0; c < cores; ++c) {
        buf[2 + c * 3] = -1.0f;
    }

    FILE* fp = fopen("/proc/stat", "re");
    if (fp) {
        buf[1] = 1.0f;
        pthread_mutex_lock(&g_cpu_mtx);
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            if (strncmp(line, "cpu", 3) != 0) continue;
            int slot = -1;
            const char* ptr = line + 3;
            if (*ptr == ' ') {
                slot = 0;
            } else if (*ptr >= '0' && *ptr <= '9') {
                int core_idx = atoi(ptr);
                if (core_idx >= 0 && core_idx < cores) {
                    slot = core_idx + 1;
                }
            }
            if (slot < 0) continue;

            unsigned long long user = 0, nice = 0, sys = 0, idle = 0, iow = 0, irq = 0, sirq = 0, steal = 0;
            while (*ptr && *ptr != ' ') ++ptr;
            int parsed = sscanf(ptr, "%llu %llu %llu %llu %llu %llu %llu %llu",
                                &user, &nice, &sys, &idle, &iow, &irq, &sirq, &steal);
            if (parsed >= 4) {
                uint64_t idle_all = idle + iow;
                uint64_t total_all = user + nice + sys + idle + iow + irq + sirq + steal;
                float pct = compute_cpu_delta(slot, total_all, idle_all);
                if (slot == 0) {
                    buf[0] = pct;
                } else {
                    buf[2 + (slot - 1) * 3] = pct;
                }
            }
        }
        pthread_mutex_unlock(&g_cpu_mtx);
        fclose(fp);
    }

    char path[96];
    for (long c = 0; c < cores; ++c) {
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%ld/cpufreq/scaling_cur_freq", c);
        int cur_khz = read_int_sysfs(path, 0);

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", c);
        int max_khz = read_int_sysfs(path, 0);
        if (max_khz <= 0) {
            snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%ld/cpufreq/scaling_max_freq", c);
            max_khz = read_int_sysfs(path, 0);
        }

        buf[2 + c * 3 + 1] = cur_khz > 0 ? (static_cast<float>(cur_khz) / 1000.0f) : 0.0f;
        buf[2 + c * 3 + 2] = max_khz > 0 ? (static_cast<float>(max_khz) / 1000.0f) : 0.0f;
    }

    env->SetFloatArrayRegion(outMetrics, 0, needed, buf);
    return static_cast<jint>(cores);
}

JNIEXPORT jint JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeReadMemAndThermal(
    JNIEnv* env, jobject, jlongArray outValues) {
    if (!outValues || env->GetArrayLength(outValues) < 8) return 0;

    jlong vals[8] = {0, 0, 0, 0, 0, 0, 0, -1};
    int status = 0;

    FILE* fp = fopen("/proc/meminfo", "re");
    if (fp) {
        status |= 1;
        char line[256];
        jlong sreclaim = 0;
        while (fgets(line, sizeof(line), fp)) {
            unsigned long long kb = 0;
            if (sscanf(line, "MemTotal: %llu kB", &kb) == 1) vals[0] = static_cast<jlong>(kb) * 1024LL;
            else if (sscanf(line, "MemAvailable: %llu kB", &kb) == 1) vals[1] = static_cast<jlong>(kb) * 1024LL;
            else if (sscanf(line, "MemFree: %llu kB", &kb) == 1) vals[2] = static_cast<jlong>(kb) * 1024LL;
            else if (sscanf(line, "Cached: %llu kB", &kb) == 1) vals[3] = static_cast<jlong>(kb) * 1024LL;
            else if (sscanf(line, "SReclaimable: %llu kB", &kb) == 1) sreclaim = static_cast<jlong>(kb) * 1024LL;
            else if (sscanf(line, "Buffers: %llu kB", &kb) == 1) vals[4] = static_cast<jlong>(kb) * 1024LL;
            else if (sscanf(line, "SwapTotal: %llu kB", &kb) == 1) vals[5] = static_cast<jlong>(kb) * 1024LL;
            else if (sscanf(line, "SwapFree: %llu kB", &kb) == 1) vals[6] = static_cast<jlong>(kb) * 1024LL;
        }
        vals[3] += sreclaim;
        fclose(fp);
    }

    char tpath[96];
    char ttype[64];
    jlong best_milli = -1;
    for (int z = 0; z < 16; ++z) {
        snprintf(tpath, sizeof(tpath), "/sys/class/thermal/thermal_zone%d/temp", z);
        int raw = read_int_sysfs(tpath, -999999);
        if (raw == -999999) continue;
        jlong milli = (raw > 0 && raw < 200) ? static_cast<jlong>(raw) * 1000LL : static_cast<jlong>(raw);
        if (milli < 5000 || milli > 125000) continue;

        snprintf(tpath, sizeof(tpath), "/sys/class/thermal/thermal_zone%d/type", z);
        bool is_cpu = false;
        if (read_str_sysfs(tpath, ttype, sizeof(ttype))) {
            std::string s(ttype);
            if (contains_ci(s, "cpu") || contains_ci(s, "soc") ||
                contains_ci(s, "tsens") || contains_ci(s, "ap") ||
                contains_ci(s, "mtk")) {
                is_cpu = true;
            }
        }
        if (is_cpu) {
            best_milli = milli;
            break;
        }
        if (best_milli < 0) best_milli = milli;
    }
    if (best_milli > 0) {
        vals[7] = best_milli;
        status |= 2;
    }

    env->SetLongArrayRegion(outValues, 0, 8, vals);
    return status;
}

JNIEXPORT jint JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeReadSystemStats(
    JNIEnv* env, jobject, jdoubleArray outStats) {
    if (!outStats || env->GetArrayLength(outStats) < 6) return 0;
    jdouble vals[6] = {0.0, 0.0, 0.0, 0.0, 0.0, -1.0};
    int status = 0;

    FILE* fp = fopen("/proc/loadavg", "re");
    if (fp) {
        double l1 = 0.0, l5 = 0.0, l15 = 0.0;
        int running = 0, total = 0;
        if (fscanf(fp, "%lf %lf %lf %d/%d", &l1, &l5, &l15, &running, &total) >= 3) {
            vals[0] = l1;
            vals[1] = l5;
            vals[2] = l15;
            vals[3] = static_cast<jdouble>(running);
            vals[4] = static_cast<jdouble>(total);
            status |= 1;
        }
        fclose(fp);
    }

    int enf = read_int_sysfs("/sys/fs/selinux/enforce", -1);
    if (enf == 0 || enf == 1) {
        vals[5] = static_cast<jdouble>(enf);
        status |= 2;
    }

    env->SetDoubleArrayRegion(outStats, 0, 6, vals);
    return status;
}

JNIEXPORT jstring JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeReadActiveSockets(JNIEnv* env, jobject) {
    std::string out;
    out.reserve(2048);
    int count = 0;
    constexpr int kMaxSocks = 80;
    bool any_ok = false;
    any_ok |= parse_proc_net("/proc/net/tcp", "TCP", false, out, count, kMaxSocks);
    any_ok |= parse_proc_net("/proc/net/tcp6", "TCP6", true, out, count, kMaxSocks);
    any_ok |= parse_proc_net("/proc/net/udp", "UDP", false, out, count, kMaxSocks);
    any_ok |= parse_proc_net("/proc/net/udp6", "UDP6", true, out, count, kMaxSocks);

    if (!any_ok) {
        return env->NewStringUTF("RESTRICTED");
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jdoubleArray JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeMeasureTcpPing(
    JNIEnv* env, jobject, jstring host, jint port, jint count) {
    jdouble res[3] = {-1.0, -1.0, -1.0};
    jdoubleArray out = env->NewDoubleArray(3);
    if (!host || !out) return out;

    const char* chost = env->GetStringUTFChars(host, nullptr);
    if (!chost) {
        env->SetDoubleArrayRegion(out, 0, 3, res);
        return out;
    }

    int probes = std::clamp(static_cast<int>(count), 1, 8);
    struct sockaddr_in sa{};
    sa.sin_family = AF_INET;
    sa.sin_port = htons(static_cast<uint16_t>(port));
    bool addr_ok = (inet_pton(AF_INET, chost, &sa.sin_addr) == 1);
    env->ReleaseStringUTFChars(host, chost);

    if (!addr_ok) {
        env->SetDoubleArrayRegion(out, 0, 3, res);
        return out;
    }

    std::vector<double> rtts;
    rtts.reserve(probes);

    for (int i = 0; i < probes; ++i) {
        int sock = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (sock < 0) continue;

        uint64_t t0 = monotonic_ns();
        int rc = connect(sock, reinterpret_cast<struct sockaddr*>(&sa), sizeof(sa));
        bool ok = false;
        if (rc == 0) {
            ok = true;
        } else if (errno == EINPROGRESS) {
            struct pollfd pfd{};
            pfd.fd = sock;
            pfd.events = POLLOUT;
            if (poll(&pfd, 1, 2000) > 0 && (pfd.revents & POLLOUT)) {
                int so_err = 0;
                socklen_t slen = sizeof(so_err);
                if (getsockopt(sock, SOL_SOCKET, SO_ERROR, &so_err, &slen) == 0 && so_err == 0) {
                    ok = true;
                }
            }
        }
        uint64_t elapsed_ns = monotonic_ns() - t0;
        close(sock);

        if (ok) {
            rtts.push_back(static_cast<double>(elapsed_ns) / 1e6);
        }
    }

    if (!rtts.empty()) {
        double min_ms = rtts[0];
        double sum_ms = 0.0;
        for (double v : rtts) {
            if (v < min_ms) min_ms = v;
            sum_ms += v;
        }
        double avg_ms = sum_ms / static_cast<double>(rtts.size());
        double jitter_ms = 0.0;
        if (rtts.size() > 1) {
            double diff_sum = 0.0;
            for (size_t i = 1; i < rtts.size(); ++i) {
                diff_sum += std::fabs(rtts[i] - rtts[i - 1]);
            }
            jitter_ms = diff_sum / static_cast<double>(rtts.size() - 1);
        }
        res[0] = min_ms;
        res[1] = avg_ms;
        res[2] = jitter_ms;
    }

    env->SetDoubleArrayRegion(out, 0, 3, res);
    return out;
}

JNIEXPORT jstring JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeScanStorage(
    JNIEnv* env, jobject, jstring rootPath, jint threadCount) {
    if (!rootPath) return env->NewStringUTF("");
    const char* croot = env->GetStringUTFChars(rootPath, nullptr);
    if (!croot) return env->NewStringUTF("");
    std::string root(croot);
    env->ReleaseStringUTFChars(rootPath, croot);

    int threads = std::clamp(static_cast<int>(threadCount), 1, 8);
    uint64_t t0 = monotonic_ns();

    ScanWorkQueue queue(root);
    std::vector<pthread_t> tids(threads);
    std::vector<ScanWorkerArg> args(threads);
    for (int i = 0; i < threads; ++i) {
        args[i].queue = &queue;
        pthread_create(&tids[i], nullptr, scan_worker_fn, &args[i]);
    }
    for (int i = 0; i < threads; ++i) {
        pthread_join(tids[i], nullptr);
    }

    uint64_t files = 0, dirs = 0, bytes = 0, errors = 0;
    uint64_t cat_count[kCatCount] = {0};
    uint64_t cat_bytes[kCatCount] = {0};
    std::vector<FileRecord> all_top;
    std::unordered_map<uint64_t, std::vector<std::string>> size_buckets;
    std::unordered_map<std::string, DirBucketStat> merged_folders;

    for (auto& w : args) {
        files += w.acc.files;
        dirs += w.acc.dirs;
        bytes += w.acc.bytes;
        errors += w.acc.errors;
        for (int c = 0; c < kCatCount; ++c) {
            cat_count[c] += w.acc.cat_count[c];
            cat_bytes[c] += w.acc.cat_bytes[c];
        }
        for (auto& f : w.acc.top_files) {
            all_top.push_back(std::move(f));
        }
        for (auto& cand : w.acc.dup_candidates) {
            if (size_buckets.size() < kMaxDupCandidates) {
                size_buckets[cand.first].push_back(std::move(cand.second));
            }
        }
        for (auto& fb : w.acc.folder_buckets) {
            auto& dst = merged_folders[fb.first];
            dst.bytes += fb.second.bytes;
            dst.files += fb.second.files;
        }
    }

    std::sort(all_top.begin(), all_top.end(), [](const FileRecord& a, const FileRecord& b) {
        return a.size > b.size;
    });
    if (all_top.size() > kTopHeavyLimit) all_top.resize(kTopHeavyLimit);

    struct FolderPair {
        std::string name;
        uint64_t bytes;
        uint64_t files;
    };
    std::vector<FolderPair> top_folders;
    top_folders.reserve(merged_folders.size());
    for (auto& kv : merged_folders) {
        if (kv.second.bytes > 0) {
            top_folders.push_back(FolderPair{kv.first, kv.second.bytes, kv.second.files});
        }
    }
    std::sort(top_folders.begin(), top_folders.end(), [](const FolderPair& a, const FolderPair& b) {
        return a.bytes > b.bytes;
    });
    if (top_folders.size() > 16) top_folders.resize(16);

    struct DupGroup {
        uint64_t size;
        uint64_t wasted;
        std::vector<std::string> paths;
    };
    std::vector<DupGroup> dup_groups;
    for (auto& kv : size_buckets) {
        if (kv.second.size() < 2) continue;
        std::unordered_map<uint64_t, std::vector<std::string>> hash_map;
        for (const auto& p : kv.second) {
            uint64_t h = fnv1a_sample_hash(p, kv.first);
            if (h != 0) hash_map[h].push_back(p);
        }
        for (auto& hkv : hash_map) {
            if (hkv.second.size() >= 2) {
                uint64_t wasted = kv.first * (hkv.second.size() - 1);
                dup_groups.push_back(DupGroup{kv.first, wasted, std::move(hkv.second)});
            }
        }
    }
    std::sort(dup_groups.begin(), dup_groups.end(), [](const DupGroup& a, const DupGroup& b) {
        return a.wasted > b.wasted;
    });
    if (dup_groups.size() > 20) dup_groups.resize(20);

    uint64_t elapsed_ms = (monotonic_ns() - t0) / 1000000ULL;

    std::string out;
    out.reserve(6144);
    char line[256];
    snprintf(line, sizeof(line), "SUM\t%llu\t%llu\t%llu\t%llu\t%llu\n",
             static_cast<unsigned long long>(files),
             static_cast<unsigned long long>(dirs),
             static_cast<unsigned long long>(bytes),
             static_cast<unsigned long long>(errors),
             static_cast<unsigned long long>(elapsed_ms));
    out.append(line);

    for (int c = 0; c < kCatCount; ++c) {
        snprintf(line, sizeof(line), "CAT\t%d\t%llu\t%llu\n",
                 c,
                 static_cast<unsigned long long>(cat_count[c]),
                 static_cast<unsigned long long>(cat_bytes[c]));
        out.append(line);
    }

    for (auto& fp : top_folders) {
        sanitize_field(fp.name);
        snprintf(line, sizeof(line), "DIR\t%llu\t%llu\t",
                 static_cast<unsigned long long>(fp.bytes),
                 static_cast<unsigned long long>(fp.files));
        out.append(line);
        out.append(fp.name);
        out.push_back('\n');
    }

    for (auto& f : all_top) {
        sanitize_field(f.name);
        sanitize_field(f.path);
        snprintf(line, sizeof(line), "BIG\t%llu\t", static_cast<unsigned long long>(f.size));
        out.append(line);
        out.append(f.name);
        out.push_back('\t');
        out.append(f.path);
        out.push_back('\n');
    }

    for (auto& dg : dup_groups) {
        snprintf(line, sizeof(line), "DUP\t%llu\t%zu",
                 static_cast<unsigned long long>(dg.size), dg.paths.size());
        out.append(line);
        for (auto& p : dg.paths) {
            sanitize_field(p);
            out.push_back('\t');
            out.append(p);
        }
        out.push_back('\n');
    }

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeCleanTempFiles(
    JNIEnv* env, jobject, jobjectArray paths) {
    if (!paths) return 0;
    jsize count = env->GetArrayLength(paths);
    uint64_t freed = 0;
    for (jsize i = 0; i < count; ++i) {
        auto jstr = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        if (!jstr) continue;
        const char* p = env->GetStringUTFChars(jstr, nullptr);
        if (p) {
            struct stat st{};
            if (lstat(p, &st) == 0 && S_ISREG(st.st_mode)) {
                if (unlink(p) == 0 && st.st_size > 0) {
                    freed += static_cast<uint64_t>(st.st_size);
                }
            }
            env->ReleaseStringUTFChars(jstr, p);
        }
        env->DeleteLocalRef(jstr);
    }
    return static_cast<jlong>(freed);
}

JNIEXPORT jdoubleArray JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeRunCpuBenchmark(
    JNIEnv* env, jobject, jint threads, jint durationMs) {
    int t = std::clamp(static_cast<int>(threads), 1, 16);
    uint64_t dur_ns = static_cast<uint64_t>(std::clamp(static_cast<int>(durationMs), 250, 2500)) * 1000000ULL;

    double int_st = run_parallel_bench(1, dur_ns, BenchMode::INT_ALU);
    double int_mt = run_parallel_bench(t, dur_ns, BenchMode::INT_ALU);
    double vec_st = run_parallel_bench(1, dur_ns, BenchMode::VEC_FMA);
    double vec_mt = run_parallel_bench(t, dur_ns, BenchMode::VEC_FMA);
    double sha_st = run_parallel_bench(1, dur_ns, BenchMode::SHA256);
    double sha_mt = run_parallel_bench(t, dur_ns, BenchMode::SHA256);
    double lz_st = run_parallel_bench(1, dur_ns, BenchMode::LZ_COMP);
    double lz_mt = run_parallel_bench(t, dur_ns, BenchMode::LZ_COMP);

    // RAM Bandwidth (shared 32 MiB A & B buffers aligned to thread count)
    constexpr size_t kBaseRam = 32ULL * 1048576ULL;
    size_t slice = ((kBaseRam / static_cast<size_t>(t)) / 64ULL) * 64ULL;
    size_t alloc_bytes = slice * static_cast<size_t>(t);
    auto* buf_a = static_cast<uint8_t*>(malloc(alloc_bytes));
    auto* buf_b = static_cast<uint8_t*>(malloc(alloc_bytes));
    double ram_read = 0.0, ram_write = 0.0, ram_copy = 0.0;
    if (buf_a && buf_b) {
        memset(buf_a, 0x5A, alloc_bytes);
        memset(buf_b, 0xA5, alloc_bytes);
        ram_read = run_parallel_bench(t, dur_ns, BenchMode::RAM_READ, buf_a, buf_b, alloc_bytes);
        ram_write = run_parallel_bench(t, dur_ns, BenchMode::RAM_WRITE, buf_a, buf_b, alloc_bytes);
        ram_copy = run_parallel_bench(t, dur_ns, BenchMode::RAM_COPY, buf_a, buf_b, alloc_bytes);
    }
    free(buf_a);
    free(buf_b);

    double dram_ns = measure_dram_latency_ns();

    double score = (int_mt * 160.0) + (vec_mt * 110.0) + (sha_mt * 1.4) +
                   (lz_mt * 2.2) + (ram_copy * 45.0) + (int_st * 240.0) +
                   (dram_ns > 1.0 ? (10000.0 / dram_ns) : 0.0);

    jdouble res[13] = {
        int_st, int_mt, vec_st, vec_mt,
        sha_st, sha_mt, lz_st, lz_mt,
        ram_read, ram_write, ram_copy, dram_ns, score
    };
    jdoubleArray out = env->NewDoubleArray(13);
    if (out) env->SetDoubleArrayRegion(out, 0, 13, res);
    return out;
}

JNIEXPORT jdouble JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeRunThrottleSlice(
    JNIEnv*, jobject, jint threads, jint sliceMs) {
    int t = std::clamp(static_cast<int>(threads), 1, 16);
    uint64_t dur_ns = static_cast<uint64_t>(std::clamp(static_cast<int>(sliceMs), 200, 2000)) * 1000000ULL;
    return static_cast<jdouble>(run_parallel_bench(t, dur_ns, BenchMode::COMBINED));
}

JNIEXPORT jdoubleArray JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeRunStorageBench(
    JNIEnv* env, jobject, jstring dirPath, jint sizeMb) {
    jdouble res[4] = {0.0, 0.0, 0.0, 0.0};
    jdoubleArray out = env->NewDoubleArray(4);
    if (!dirPath || !out) return out;

    const char* cdir = env->GetStringUTFChars(dirPath, nullptr);
    if (!cdir) return out;
    std::string fpath(cdir);
    env->ReleaseStringUTFChars(dirPath, cdir);
    if (fpath.empty()) return out;
    if (fpath.back() != '/') fpath.push_back('/');
    fpath.append(".novadeck_io_bench.tmp");

    int mb = std::clamp(static_cast<int>(sizeMb), 8, 128);
    constexpr size_t kBlock = 262144;
    size_t total_bytes = static_cast<size_t>(mb) * 1048576ULL;
    size_t blocks = total_bytes / kBlock;

    auto* buf = static_cast<uint8_t*>(malloc(kBlock));
    if (!buf) {
        env->SetDoubleArrayRegion(out, 0, 4, res);
        return out;
    }
    for (size_t i = 0; i < kBlock; ++i) {
        buf[i] = static_cast<uint8_t>((i * 131 + 17) & 0xFF);
    }

    int fd = open(fpath.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd >= 0) {
        uint64_t tw0 = monotonic_ns();
        size_t written_total = 0;
        for (size_t b = 0; b < blocks; ++b) {
            ssize_t w = write(fd, buf, kBlock);
            if (w > 0) written_total += static_cast<size_t>(w);
        }
        fsync(fd);
        uint64_t tw_ns = monotonic_ns() - tw0;
        if (tw_ns > 0 && written_total > 0) {
            res[0] = (static_cast<double>(written_total) / 1048576.0) / (static_cast<double>(tw_ns) / 1e9);
        }

        lseek(fd, 0, SEEK_SET);
        uint64_t tr0 = monotonic_ns();
        size_t read_total = 0;
        uint64_t checksum = 0;
        for (size_t b = 0; b < blocks; ++b) {
            ssize_t r = read(fd, buf, kBlock);
            if (r > 0) {
                read_total += static_cast<size_t>(r);
                checksum += buf[0] + buf[r - 1];
            }
        }
        uint64_t tr_ns = monotonic_ns() - tr0;
        if (tr_ns > 0 && read_total > 0) {
            res[1] = (static_cast<double>(read_total) / 1048576.0) / (static_cast<double>(tr_ns) / 1e9);
        }

        constexpr int kRandOps = 1024;
        uint64_t num_4k = total_bytes / 4096ULL;
        uint64_t seed = 0x9e3779b97f4a7c15ULL;
        uint64_t tr40 = monotonic_ns();
        size_t r4_bytes = 0;
        for (int op = 0; op < kRandOps; ++op) {
            seed = seed * 6364136223846793005ULL + 1ULL;
            off_t off = static_cast<off_t>((seed % num_4k) * 4096ULL);
            ssize_t r = pread(fd, buf, 4096, off);
            if (r > 0) {
                r4_bytes += static_cast<size_t>(r);
                checksum += buf[0];
            }
        }
        uint64_t tr4_ns = monotonic_ns() - tr40;
        volatile uint64_t csink = checksum;
        (void)csink;
        if (tr4_ns > 0 && r4_bytes > 0) {
            double secs = static_cast<double>(tr4_ns) / 1e9;
            res[2] = (static_cast<double>(r4_bytes) / 1048576.0) / secs;
            res[3] = static_cast<double>(kRandOps) / secs;
        }

        close(fd);
        unlink(fpath.c_str());
    }

    free(buf);
    env->SetDoubleArrayRegion(out, 0, 4, res);
    return out;
}

JNIEXPORT jint JNICALL
Java_io_novadeck_control_core_NativeBridge_nativeCheckRootIndicators(JNIEnv*, jobject) {
    int flags = 0;
    static const char* kSuPaths[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/local/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/debug_ramdisk/su"
    };
    for (const char* p : kSuPaths) {
        if (access(p, F_OK) == 0) {
            flags |= 1;
            break;
        }
    }

    static const char* kMagiskPaths[] = {
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/data/adb/ap",
        "/sbin/.magisk"
    };
    for (const char* p : kMagiskPaths) {
        if (access(p, F_OK) == 0) {
            flags |= 2;
            break;
        }
    }

    FILE* fp = fopen("/proc/mounts", "re");
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            std::string s(line);
            if (contains_ci(s, "magisk") || contains_ci(s, "kernelsu")) {
                flags |= 4;
                break;
            }
        }
        fclose(fp);
    }

    return flags;
}

} // extern "C"
