cmake_minimum_required(VERSION 3.22.1)
project(dogsc-color LANGUAGES CXX)

add_library(dogsc-color SHARED dogsc-color.cpp)
target_compile_options(dogsc-color PRIVATE -O3 -ffast-math)
