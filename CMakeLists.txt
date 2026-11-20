cmake_minimum_required(VERSION 3.21)
project(StreamForge VERSION 0.1.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTOUIC ON)
set(CMAKE_AUTORCC ON)
find_package(Qt6 6.5 REQUIRED COMPONENTS Widgets)
add_executable(streamforge
  app/main.cpp
  app/mainwindow.cpp
  app/mainwindow.h
  engine/obsengine.cpp
  engine/obsengine.h
)
target_link_libraries(streamforge PRIVATE Qt6::Widgets)
target_include_directories(streamforge PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/app
  ${CMAKE_CURRENT_SOURCE_DIR}/engine
)
find_path(LIBOBS_INCLUDE_DIR obs.h HINTS ENV LIBOBS_INCLUDE_DIR PATH_SUFFIXES obs libobs)
find_library(LIBOBS_LIBRARY NAMES obs libobs HINTS ENV LIBOBS_LIBRARY_DIR)
if(LIBOBS_INCLUDE_DIR AND LIBOBS_LIBRARY)
  target_include_directories(streamforge PRIVATE ${LIBOBS_INCLUDE_DIR})
  target_link_libraries(streamforge PRIVATE ${LIBOBS_LIBRARY})
  target_compile_definitions(streamforge PRIVATE STREAMFORGE_HAS_LIBOBS=1)
else()
  target_compile_definitions(streamforge PRIVATE STREAMFORGE_HAS_LIBOBS=0)
  message(STATUS "libobs not found: building the desktop UI shell only.")
endif()
if(MSVC)
  target_compile_options(streamforge PRIVATE /W4 /permissive-)
else()
  target_compile_options(streamforge PRIVATE -Wall -Wextra -Wpedantic)
endif()
install(TARGETS streamforge RUNTIME DESTINATION bin BUNDLE DESTINATION .)
