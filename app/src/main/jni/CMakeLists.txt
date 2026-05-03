cmake_minimum_required(VERSION 3.22)
project(nospoon_exec C)

add_library(nospoon_exec SHARED nospoon_exec.c)
target_link_libraries(nospoon_exec log)
