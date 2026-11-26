cmake_minimum_required(VERSION 3.22.1)
project(novadeck_native LANGUAGES CXX)

add_library(
    novadeck_native
    SHARED
    native-core.cpp
)

target_compile_features(novadeck_native PRIVATE cxx_std_17)

target_compile_options(
    novadeck_native
    PRIVATE
    -O3
    -DNDEBUG
    -ffast-math
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -Wall
    -Wextra
    -Wno-unused-parameter
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    novadeck_native
    ${android-lib}
    ${log-lib}
)
