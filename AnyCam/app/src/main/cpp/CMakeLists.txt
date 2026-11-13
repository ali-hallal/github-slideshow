cmake_minimum_required(VERSION 3.22.1)
project(anycam CXX)
set(CMAKE_CXX_STANDARD 17)
add_library(anycam SHARED stack.cpp)
# Fast, but the same arithmetic as the Kotlin reference (no reordering of sums).
target_compile_options(anycam PRIVATE -O3 -fno-fast-math -ffp-contract=off)
