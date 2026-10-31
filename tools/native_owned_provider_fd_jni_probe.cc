// EXPERIMENT ONLY: JNI proof of actually passing an Android-created
// seekable, owner-verified PFD to the isolated provider-FD research writer.
// This method cannot create, query, authorize, delete or publish a URI. The
// Kotlin test separately verifies its disposable platform-owned row. NEVER
// expose this method as general permission to write arbitrary passed FDs.
#include "native_owned_provider_fd.h"

#include <jni.h>
#include <array>
#include <string>
#include <unistd.h>

using chunks::research::PinnedProviderFd;

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gamesbyian_chunks_engine_OwnedProviderFdAndroidProbe_nativeRoundTrip(
    JNIEnv* env, jobject /* instance */, jint fd, jlong declared,
    jstring generation) {
  // fd comes only from ParcelFileDescriptor.detachFd(): JNI consumes this
  // integer on EVERY path. After Bind makes a private duplicate, close the
  // original immediately so no Kotlin alias can flip shared O_APPEND flags.
  if (fd < 0) return JNI_FALSE;
  if (declared != 65552 || generation == nullptr) {
    ::close(fd);
    return JNI_FALSE;
  }
  const char* token = env->GetStringUTFChars(generation, nullptr);
  if (!token) {
    ::close(fd);
    return JNI_FALSE;
  }
  const std::string validated_token(token);
  env->ReleaseStringUTFChars(generation, token);
  std::string error;
  auto owner = PinnedProviderFd::Bind(fd, static_cast<std::uint64_t>(declared),
                                     validated_token, &error);
  ::close(fd);  // no remaining app-held alias when native piece writes begin
  if (!owner) return JNI_FALSE;
  std::array<char, 4096> first{};
  std::array<char, 16> second{};
  for (std::size_t i = 0; i < first.size(); ++i)
    first[i] = static_cast<char>((i * 37 + 11) & 255);
  for (std::size_t i = 0; i < second.size(); ++i)
    second[i] = static_cast<char>((i * 17 + 3) & 255);
  if (!owner->WriteAt(0, first.data(), first.size(), &error) ||
      !owner->WriteAt(65536, second.data(), second.size(), &error) ||
      !owner->Sync(&error)) return JNI_FALSE;

  std::array<char, 4096> actual_first{};
  std::array<char, 16> actual_second{};
  if (!owner->ReadAt(0, actual_first.data(), actual_first.size(), &error) ||
      !owner->ReadAt(65536, actual_second.data(), actual_second.size(), &error))
    return JNI_FALSE;
  return (actual_first == first && actual_second == second)
             ? JNI_TRUE : JNI_FALSE;
}
