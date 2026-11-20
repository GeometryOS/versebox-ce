cmake_minimum_required(VERSION 3.22.1)
project(phantom-probe CXX)

add_library(phantom-probe SHARED phantom_probe.cpp)

target_compile_options(phantom-probe PRIVATE -Os -fvisibility=hidden)
target_link_options(phantom-probe PRIVATE -Wl,--build-id=none)
