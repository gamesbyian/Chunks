// Disposable decoder selection experiment. No application integration or output writer.
#include <cstdio>
#include <cstdlib>
#include <cwchar>
#include <cstring>
#include <string>
#include <vector>
#include <cstdint>
#include <utility>
#ifdef CHUNKS_UNRAR_JNI
#include <jni.h>
#endif
#include "dll.hpp"
#ifdef __ANDROID__
#include <sys/resource.h>
#include <unistd.h>
#endif

struct Witness {
    std::string next;
    std::wstring nextWide;
    std::vector<unsigned char> bytes;
    size_t limit;
    bool narrowSeen = false;
    bool wideSeen = false;
    bool refused = false;
};

static int CALLBACK receive(UINT message, LPARAM user, LPARAM p1, LPARAM p2) {
    auto &w = *reinterpret_cast<Witness *>(user);
    auto refuse = [&]() { w.refused = true; return -1; };
    switch (message) {
    case UCM_PROCESSDATA:
        if (p1 == 0 || p2 <= 0 || static_cast<size_t>(p2) > w.limit - w.bytes.size())
            return refuse();
        try {
            const auto *data = reinterpret_cast<const unsigned char *>(p1);
            w.bytes.insert(w.bytes.end(), data, data + p2);
        } catch (...) { return refuse(); }
        return 1;
    case UCM_CHANGEVOLUME:
        if (p2 != RAR_VOL_NOTIFY || !p1 || w.narrowSeen ||
            w.next != reinterpret_cast<const char *>(p1))
            return refuse();
        w.narrowSeen = true;
        return 1;
    case UCM_CHANGEVOLUMEW:
        if (p2 != RAR_VOL_NOTIFY || !p1 || w.wideSeen ||
            w.nextWide != reinterpret_cast<const wchar_t *>(p1))
            return refuse();
        w.wideSeen = true;
        return 1;
    default:
        // No passwords, dictionary overrides, guessed names or other callbacks.
        return refuse();
    }
}

// Shared fixed-fixture decoder for both disposable transports. Never calls
// RAR_EXTRACT, never accepts arbitrary members or writes an output path.
static int decodeFixture(const std::string &directory, size_t limit,
                         std::vector<unsigned char> &decoded) {
    if (limit != 1048 && limit != 1047) return 2;
    const std::string first = directory + "/Verified.part1.rar";
    Witness w;
    w.next = directory + "/Verified.part2.rar";
    w.nextWide.assign(w.next.begin(), w.next.end());
    w.limit = limit;
    RAROpenArchiveDataEx open{};
    open.ArcName = const_cast<char *>(first.c_str());
    open.OpenMode = RAR_OM_EXTRACT;
    open.Callback = receive;
    open.UserData = reinterpret_cast<LPARAM>(&w);
    open.MaxAllocate = 128;  // MiB, independent process address-space limit also applied.
    open.MaxRuntime = 10;
    open.MaxDiskWrite = 1;
    auto handle = RAROpenArchiveEx(&open);
    if (!handle || open.OpenResult != ERAR_SUCCESS) {
        if (handle) RARCloseArchive(handle);
        std::fprintf(stderr, "open refused: %u\n", open.OpenResult);
        return 3;
    }
    auto finish = [&](int code) {
        const int closed = RARCloseArchive(handle);
        return code == 0 && closed != ERAR_SUCCESS ? 3 : code;
    };
    if (w.refused || (open.Flags & (ROADF_VOLUME | ROADF_FIRSTVOLUME)) !=
        (ROADF_VOLUME | ROADF_FIRSTVOLUME) || (open.Flags & ROADF_ENCHEADERS))
        return finish(3);
    RARHeaderDataEx header{};
    int status = RARReadHeaderEx(handle, &header);
    if (status != ERAR_SUCCESS || w.refused ||
        std::wcscmp(header.FileNameW, L"known.bin") != 0 ||
        (header.Flags & (RHDF_ENCRYPTED | RHDF_DIRECTORY | RHDF_SPLITBEFORE)) ||
        header.RedirType != 0 || header.UnpSizeHigh != 0 || header.UnpSize != 1048 ||
        header.UnpSize > w.limit) {
        std::fprintf(stderr, "member refused: %d\n", status);
        return finish(3);
    }
    // RAR_TEST performs the native checksum verification and sends decoded
    // bytes to the callback. RAR_EXTRACT is deliberately never called.
    status = RARProcessFile(handle, RAR_TEST, nullptr, nullptr);
    if (status != ERAR_SUCCESS || w.refused || w.bytes.size() != 1048 ||
        !w.narrowSeen || !w.wideSeen) {
        std::fprintf(stderr, "test refused: %d bytes=%zu narrow=%d wide=%d\n",
                     status, w.bytes.size(), w.narrowSeen, w.wideSeen);
        return finish(3);
    }
    RARHeaderDataEx end{};
    if (RARReadHeaderEx(handle, &end) != ERAR_END_ARCHIVE || w.refused)
        return finish(3);
    if (finish(0) != 0) return 3;
    decoded = std::move(w.bytes);
    return 0;
}

#ifndef CHUNKS_UNRAR_JNI
int main(int argc, char **argv) {
    if (argc != 3) return 2;
#ifdef __ANDROID__
    // Android shell subprocess only. Scudo reserves large virtual regions,
    // so do not use a host RLIMIT_AS ceiling as an Android memory proof.
    // The decoder's MaxAllocate, output callback and process CPU/file caps
    // remain separate; production process/resource isolation is unproved.
    const struct rlimit cpu = {15, 16}, zero = {0, 0};
    if (setrlimit(RLIMIT_CPU, &cpu) || setrlimit(RLIMIT_FSIZE, &zero) ||
        setrlimit(RLIMIT_CORE, &zero)) return 2;
    alarm(18);
#endif
    const std::string limitText = argv[2];
    if (limitText != "1048" && limitText != "1047") return 2;
    std::vector<unsigned char> bytes;
    const int status = decodeFixture(argv[1], static_cast<size_t>(std::stoul(limitText)), bytes);
    if (status != 0) return status;
    return std::fwrite(bytes.data(), 1, bytes.size(), stdout) == bytes.size() ? 0 : 4;
}
#else
// Only the separately packaged synthetic witness calls this; no shipping
// native API or production privileges. Do not change process-wide rlimits.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_gamesbyian_chunks_unrarprobe_WitnessInstrumentation_decode(
    JNIEnv *env, jclass, jstring root, jint limit) {
    const char *raw = env->GetStringUTFChars(root, nullptr);
    if (!raw) return nullptr;
    std::string directory;
    try { directory.assign(raw); }
    catch (...) {
        env->ReleaseStringUTFChars(root, raw);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "fixture root allocation");
        return nullptr;
    }
    env->ReleaseStringUTFChars(root, raw);
    try {
        std::vector<unsigned char> bytes;
        const int status = decodeFixture(directory, static_cast<size_t>(limit), bytes);
        if (status == 3) return nullptr;
        if (status != 0 || bytes.size() != 1048) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "unexpected decoder status");
            return nullptr;
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte *>(bytes.data()));
        return result;
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "unexpected native failure");
        return nullptr;
    }
}
#endif
