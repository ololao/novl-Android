# SPDX-License-Identifier: Apache-2.0

cmake_minimum_required(VERSION 3.4.1)
project("Novl")

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
add_link_options(LINKER:--build-id=none)
add_compile_options(-Wall)

add_library(wg-go SHARED libwg-go/jni.c libwg-go/novl_engine.c)
target_link_libraries(wg-go log)
