cmake_minimum_required(VERSION 3.16)
project(chunks_native_owned_writer LANGUAGES CXX)

# Experimental backing primitive only. Does not install into libtorrent4j,
# ship in the Android APK, or satisfy disk_interface release acceptance.
add_library(chunks_owned_writer STATIC
    owned_payload_file.cc
    owned_torrent_storage.cc
)
target_compile_features(chunks_owned_writer PUBLIC cxx_std_17)
target_include_directories(chunks_owned_writer PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(chunks_owned_writer PRIVATE -Wall -Wextra -Werror -pedantic)
endif()

option(CHUNKS_OWNED_WRITER_HOST_TESTS "Build host-only native regression tests" ON)
if(CHUNKS_OWNED_WRITER_HOST_TESTS AND NOT ANDROID)
    find_package(Threads REQUIRED)
    add_executable(chunks_owned_file_test owned_payload_file_test.cc)
    target_link_libraries(chunks_owned_file_test PRIVATE chunks_owned_writer Threads::Threads)
    add_executable(chunks_owned_multifile_test owned_torrent_storage_test.cc)
    target_link_libraries(chunks_owned_multifile_test PRIVATE chunks_owned_writer Threads::Threads)
    enable_testing()
    add_test(NAME owned_file_nonclobber COMMAND chunks_owned_file_test)
    add_test(NAME owned_multifile_nonclobber COMMAND chunks_owned_multifile_test)
endif()
