cmake_minimum_required(VERSION 3.22.1)
project(mybox_probe)
add_library(mybox-probe SHARED probe.cpp)
target_link_options(mybox-probe PRIVATE "-Wl,-z,max-page-size=16384")
