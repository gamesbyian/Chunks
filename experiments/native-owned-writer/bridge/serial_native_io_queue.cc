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

void SerialNativeIoQueue::WaitIdle() {
  std::unique_lock<std::mutex> lock(mutex_);
  idle_.wait(lock, [this] { return pending_.empty() && !active_; });
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
    bool unsafe = false;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      ready_.wait(lock, [this] { return stopping_ || !pending_.empty(); });
      if (pending_.empty()) {
        idle_.notify_all();
        return;
      }
      entry = std::move(pending_.front());
      pending_.pop_front();
      active_ = true;
      unsafe = faulted_;
    }
    if (unsafe) {
      // Previously unreportable native failure: skip every pending disk op.
      try { entry.on_error(); } catch (...) {}
    } else {
      try {
        entry.task();
      } catch (...) {
        try {
          entry.on_error();
        } catch (...) {
          std::lock_guard<std::mutex> lock(mutex_);
          faulted_ = true;
        }
      }
    }
    {
      std::lock_guard<std::mutex> lock(mutex_);
      active_ = false;
    }
    idle_.notify_all();
  }
}

} // namespace chunks
