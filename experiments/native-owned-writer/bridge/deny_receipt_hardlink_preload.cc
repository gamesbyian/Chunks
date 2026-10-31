#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>

// Disposable Linux host acceptance ONLY. When the actual rebuilt libtorrent
// custom writer tries to publish a private .tmp- receipt through linkat,
// simulate Android app-private EPERM. This must execute during positive
// libtorrent loopback download AND foreign-file collision refusals.
// In any real app or Android binary this source must NEVER be compiled.
extern "C" int linkat(int olddirfd, const char* oldpath,
                        int newdirfd, const char* newpath, int flags) {
  if (std::strncmp(oldpath, ".tmp-", 5) == 0) {
    static constexpr char marker[] =
        "CHUNKS_HOST_RECEIPT_LINKAT_DENIED=EPERM\\n";
    const ssize_t emitted = ::write(STDERR_FILENO, marker, sizeof(marker) - 1);
    (void)emitted;
    errno = EPERM;
    return -1;
  }
  using LinkAt = int (*)(int, const char*, int, const char*, int);
  // Resolve the real function at runtime for unrelated operations. If
  // resolution fails, deny rather than attempting a recursive fallback.
  static LinkAt original = []() -> LinkAt {
    void* handle = ::dlsym(RTLD_NEXT, "linkat");
    return reinterpret_cast<LinkAt>(handle);
  }();
  if (!original) { errno = ENOSYS; return -1; }
  return original(olddirfd, oldpath, newdirfd, newpath, flags);
}
