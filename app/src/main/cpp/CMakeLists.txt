cmake_minimum_required(VERSION 3.22.1)
project("padengine")

find_package(oboe REQUIRED CONFIG)

add_library(padengine SHARED
    native-lib.cpp
    PadEngine.cpp
)

target_link_libraries(padengine
    oboe::oboe
    log
    android
)
