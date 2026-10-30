#include "serial_native_io_queue.h"

#include <stdexcept>
#include <utility>

namespace chunks {

SerialNativeIoQueue::SerialNativeIoQueue(std::size_t max_pending)
    : limit_(max_pending), worker_([this] { Run(); }) {
  if (max_pending == 0) {
    // Invalid limits are a programming error, not a request for unbounded IO.
    DrainAndStop();
    throw std::invalid_argument("Native disk queue needs a positive bound");
  }
}

SerialNativeIoQueue::~SerialNativeIoQueue() { DrainAndStop(); }

bool SerialNativeIoQueue::Submit(Task task, Task on_error) {
  if (!task || !on_error) return false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_ || faulted_ || pending_.size() >= limit_) return false;
    pending_.push_back({std::move(task), std::move(on_error)});
  }
  ready_.notify_one();
  return true;
}

void SerialNativeIoQueue::DrainAndStop() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    stopping_ = true;
  }
  ready_.notify_all();
  if (worker_.joinable()) {
    // NativeDisk::abort is called on libtorrent's network thread, never on
    // this worker. A self-join is therefore a contract violation.
    if (worker_.get_id() == std::this_thread::get_id())
      std::terminate();
    worker_.join();
  }
}

std::size_t SerialNativeIoQueue::pending() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return pending_.size();
}

bool SerialNativeIoQueue::faulted() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return faulted_;
}

void SerialNativeIoQueue::Run() {
  for (;;) {
    Entry entry;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      ready_.wait(lock, [this] { return stopping_ || !pending_.empty(); });
      if (pending_.empty()) return;
      entry = std::move(pending_.front());
      pending_.pop_front();
    }
    bool unsafe = false;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      unsafe = faulted_;
    }
    if (unsafe) {
      // Do not run another disk operation after an unreportable worker
      // exception. Still issue each queued callback's error path.
      try { entry.on_error(); } catch (...) {}
      continue;
    }
    try {
      entry.task();
    } catch (...) {
      try {
        entry.on_error();
      } catch (...) {
        // No further jobs may execute when callback failure is uncertain.
        // Existing accepted tasks remain in memory and cannot be forgotten.
        std::lock_guard<std::mutex> lock(mutex_);
        faulted_ = true;
      }
    }
  }
}

} // namespace chunks
