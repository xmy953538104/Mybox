cmake_minimum_required(VERSION 3.22.1)
project(mybox LANGUAGES CXX)

add_library(blackbox SHARED
    BoxCore.cpp
    IO.cpp
    Hook/BaseHook.cpp
    Hook/BinderHook.cpp
    Hook/UnixFileSystemHook.cpp
    JniHook/JniHook.cpp
)
target_include_directories(blackbox PRIVATE . JniHook)
target_compile_features(blackbox PRIVATE cxx_std_17)
target_compile_options(blackbox PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-writable-strings)
target_link_options(blackbox PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(blackbox PRIVATE log)
