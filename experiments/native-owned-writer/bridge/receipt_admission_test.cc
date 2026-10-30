#include "receipt_admission.h"

#include <cassert>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <unistd.h>

namespace fs = std::filesystem;
using chunks::NativeAdmissionMode;
using chunks::ClassifyNativeAdmission;
using chunks::OwnedTorrentStorage;
constexpr char A[] = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
constexpr char B[] = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
struct Case {
  fs::path base;
  fs::path receipts;
  Case() {
    char format[]="/tmp/chunks-receipts-XXXXXX";
    char* path=mkdtemp(format);
    assert(path);
    base=path; receipts=base/"private";
    fs::create_directories(receipts);
  }
  ~Case() { fs::remove_all(base); }
  void add(std::string const& name) {
    std::ofstream out(receipts/name);
    out << "receipt fixture only";
  }
  std::vector<OwnedTorrentStorage::FileSpec> layout() const {
    return {{"first.bin",32,A,A,false}, {"second.bin",40,B,B,false}};
  }
};
int main() {
  int passed=0;
  {
    Case c; std::string error;
    assert(ClassifyNativeAdmission(c.receipts.string(),c.layout(),&error)==
           NativeAdmissionMode::FRESH);
    assert(error.empty()); ++passed;
  }
  {
    Case c; c.add(std::string(A)+".pending");c.add(std::string(B)+".pending");
    std::string error;
    assert(ClassifyNativeAdmission(c.receipts.string(),c.layout(),&error)==
           NativeAdmissionMode::RESTORED);
    ++passed;
  }
  {
    Case c; c.add(A);c.add(B);
    std::string error;
    assert(ClassifyNativeAdmission(c.receipts.string(),c.layout(),&error)==
           NativeAdmissionMode::RESTORED);
    ++passed;
  }
  {
    Case c; c.add(std::string(A)+".pending");
    std::string error;
    // Crash during stopped Add: pending-but-never-finalized claims can
    // safely be re-reserved with exact mapping checks, without overwriting.
    assert(ClassifyNativeAdmission(c.receipts.string(),c.layout(),&error)==
           NativeAdmissionMode::FRESH);
    assert(error.empty()); ++passed;
  }
  {
    Case c; c.add(A);c.add(std::string(B)+".pending");
    std::string error;
    assert(ClassifyNativeAdmission(c.receipts.string(),c.layout(),&error)==
           NativeAdmissionMode::RESTORED);
    ++passed;
  }
  {
    Case c; std::string error;
    auto x=c.layout();x[0].receipt_id="../../escape";
    assert(ClassifyNativeAdmission(c.receipts.string(),x,&error)==
           NativeAdmissionMode::REFUSED);
    ++passed;
  }
  {
    Case c; std::string error;
    auto x=c.layout();x[0].pad_file=true;x[1].size=0;
    assert(ClassifyNativeAdmission(c.receipts.string(),x,&error)==
           NativeAdmissionMode::REFUSED);
    ++passed;
  }
  {
    Case c;std::string error;
    fs::create_symlink(c.receipts,c.base/"indirect");
    assert(ClassifyNativeAdmission((c.base/"indirect").string(),c.layout(),&error)==
           NativeAdmissionMode::REFUSED);
    ++passed;
  }
  {
    Case c;std::string error;
    c.add(A);c.add(B);
    fs::remove(c.receipts/A);
    fs::create_symlink("/tmp/nonexistent", c.receipts/A);
    // Presence isn't trust: it deliberately classifies as restored, whose
    // later Open/Reopen parses and refuses the symlinked receipt.
    assert(ClassifyNativeAdmission(c.receipts.string(),c.layout(),&error)==
           NativeAdmissionMode::RESTORED);
    ++passed;
  }
  {
    Case c; c.add(A);
    std::string error;
    assert(ClassifyNativeAdmission(c.receipts.string(),c.layout(),&error)==
           NativeAdmissionMode::REFUSED);
    assert(!error.empty()); ++passed;
  }
  std::cout << "CHUNKS_RECEIPT_ADMISSION_CASES=" << passed << " PASSED\n";
}
