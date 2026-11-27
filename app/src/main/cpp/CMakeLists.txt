cmake_minimum_required(VERSION 3.22.1)

project("usb_dac_native")

add_library(
    usb_dac_native
    SHARED
    usb_dac_native.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    usb_dac_native
    ${log-lib}
)
