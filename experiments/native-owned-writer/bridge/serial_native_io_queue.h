#pragma once

#include <condition_variable>
#include <cstddef>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>

namespace chunks {

// Single-owner bounded serial disk queue. All writes, reads, hashes, flushes
// and state transitions to an OwnedTorrentStorage must execute on this thread.
// Separate from libtorrent's network thread. No IO runs on a rejected submit.
class SerialNativeIoQueue final {
 public:
  using Task = std::function<void()>;
  explicit SerialNativeIoQueue(std::size_t max_pending = 64);
  ~SerialNativeIoQueue();

  SerialNativeIoQueue(SerialNativeIoQueue const&) = delete;
  SerialNativeIoQueue& operator=(SerialNativeIoQueue const&) = delete;

  // Every accepted task must report its own callback. If it throws, on_error
  // runs in the worker before later tasks; no exception escapes the thread.
  // A rejected task is never executed. Caller must report rejection itself.
  bool Submit(Task task, Task on_error);
  // Drain all accepted jobs and join. Idempotent; not called from the worker.
  void DrainAndStop();
  std::size_t pending() const;
  bool faulted() const;

 private:
  struct Entry { Task task; Task on_error; };
  void Run();
  mutable std::mutex mutex_;
  std::condition_variable ready_;
  std::deque<Entry> pending_;
  std::thread worker_;
  std::size_t limit_;
  bool stopping_ = false;
  bool faulted_ = false;
};
} // namespace chunks
