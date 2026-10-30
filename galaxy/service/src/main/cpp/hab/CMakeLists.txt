cmake_minimum_required(VERSION 3.22)
project(galaxy_hab LANGUAGES C)

# CMake供独立静态库/QNX SDK构建；APK的自有PIE发送程序由build_hab_bridge.py编译。
# QNX接收进程单独链接目标系统库；自有候选作为AutoService部署资产，厂商库不进入APK。
add_library(galaxy_hab STATIC
    galaxy_hab_transport.c
    galaxy_hab_linux.c
    galaxy_hab_pixels.c
    galaxy_hab_protocol.c
    galaxy_hab_sender.c
    galaxy_hab_hmac.c
    galaxy_hab_receiver.c
)
target_include_directories(galaxy_hab PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(galaxy_hab PRIVATE c_std_11)
target_compile_options(galaxy_hab PRIVATE -Wall -Wextra -Wpedantic -Werror)
set_target_properties(galaxy_hab PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(CMAKE_SYSTEM_NAME STREQUAL "QNX")
    add_executable(galaxy_hab_qnx_receiver galaxy_hab_qnx.c galaxy_hab_qnx_main.c)
    target_compile_features(galaxy_hab_qnx_receiver PRIVATE c_std_11)
    target_compile_options(galaxy_hab_qnx_receiver PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(galaxy_hab_qnx_receiver PRIVATE galaxy_hab screen uhab-qvmhost pmem_client)
endif()
