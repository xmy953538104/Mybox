cmake_minimum_required(VERSION 3.22.1)
project(mybox LANGUAGES C CXX ASM)

set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/dobby EXCLUDE_FROM_ALL)

add_library(blackbox SHARED
    BoxCore.cpp
    IO.cpp
    Hook/BaseHook.cpp
    Hook/UnixFileSystemHook.cpp
    Hook/LibcHook.cpp
    JniHook/JniHook.cpp
)
target_include_directories(blackbox PRIVATE . JniHook)
target_compile_features(blackbox PRIVATE cxx_std_17)
target_compile_options(blackbox PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-writable-strings)
target_link_options(blackbox PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(blackbox PRIVATE dobby_static log dl)

# Built on demand, outside the app, so the same rule implementation runs on a host or device.
add_executable(path-rules-test EXCLUDE_FROM_ALL ../../test/cpp/PathRulesTest.cpp)
target_compile_features(path-rules-test PRIVATE cxx_std_17)
