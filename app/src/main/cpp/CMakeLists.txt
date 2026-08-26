cmake_minimum_required(VERSION 3.22.1)
project(logsilence)

add_library(logsilence SHARED log_silence.cpp)
find_library(log-lib log)
target_link_libraries(logsilence ${log-lib})
