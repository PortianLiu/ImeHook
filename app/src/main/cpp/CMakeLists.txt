cmake_minimum_required(VERSION 3.22.1)
project(imehook CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Dobby 上游（jmpews/Dobby）master 对 Android 构建已坏且仓库归档，
# 使用 LSPosed 维护的 fork（LSPosed 本体在用）。
# github.com 直连不稳，vendor  tarball（LSPosed/Dobby @ c1da0315），构建零网络依赖。
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
        Dobby
        URL ${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/Dobby-c1da0315.tar.gz
)
FetchContent_MakeAvailable(Dobby)

add_library(imehook SHARED imehook.cpp)

# Dobby 公开 API 头文件目录
target_include_directories(imehook PRIVATE ${dobby_SOURCE_DIR}/include)

find_library(log-lib log)
target_link_libraries(imehook PRIVATE dobby ${log-lib})
