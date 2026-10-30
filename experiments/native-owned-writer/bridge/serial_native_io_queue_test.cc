#include "serial_native_io_queue.h"

#include <atomic>
#include <cassert>
#include <future>
#include <iostream>
#include <stdexcept>
#include <vector>

using chunks::SerialNativeIoQueue;

int main() {
  int cases = 0;
  {
    SerialNativeIoQueue queue(1);
    std::promise<void> began, release;
    auto started = began.get_future();
    auto gate = release.get_future().share();
    std::vector<int> order;
    assert(queue.Submit([&] {
      began.set_value();
      gate.wait();
      order.push_back(1);
    }, [] { assert(false); }));
    started.wait();  // prove first task is running, not just enqueued
    assert(queue.Submit([&] { order.push_back(2); }, [] { assert(false); }));
    assert(queue.pending() == 1);
    assert(!queue.Submit([&] { order.push_back(99); }, [] { assert(false); }));
    release.set_value();
    queue.DrainAndStop();
    assert((order == std::vector<int>{1, 2}));
    assert(queue.pending() == 0);
    assert(!queue.Submit([] {}, [] {}));
    queue.DrainAndStop(); // idempotent
    ++cases;
  }
  {
    SerialNativeIoQueue queue;
    std::atomic<int> errors{0}, completions{0};
    assert(queue.Submit([] { throw std::runtime_error("native IO failed"); },
                        [&] { ++errors; }));
    assert(queue.Submit([&] { ++completions; }, [] { assert(false); }));
    queue.DrainAndStop();
    assert(errors == 1);
    assert(completions == 1);
    assert(!queue.faulted()); // a delivered error is a valid outcome
    ++cases;
  }
  {
    SerialNativeIoQueue queue;
    std::atomic<int> writes{0}, refused{0};
    std::promise<void> entered, release;
    auto waiting = entered.get_future();
    auto gate = release.get_future().share();
    assert(queue.Submit([&] {
      entered.set_value();
      gate.wait();
      throw std::runtime_error("job failed");
    }, [] { throw std::runtime_error("failed error callback"); }));
    waiting.wait();
    assert(queue.Submit([&] { ++writes; }, [&] { ++refused; }));
    release.set_value();
    queue.DrainAndStop();
    assert(writes == 0);
    assert(refused == 1);
    assert(queue.faulted());
    assert(!queue.Submit([&] { ++writes; }, [&] { ++refused; }));
    ++cases;
  }
  {
    SerialNativeIoQueue queue;
    assert(!queue.Submit({}, [] {}));
    assert(!queue.Submit([] {}, {}));
    queue.DrainAndStop();
    ++cases;
  }
  {
    bool refused = false;
    try { SerialNativeIoQueue queue(0); }
    catch (std::invalid_argument const&) { refused = true; }
    assert(refused);
    ++cases;
  }
  std::cout << "CHUNKS_SERIAL_NATIVE_IO_CASES=" << cases << " PASSED\n";
}
