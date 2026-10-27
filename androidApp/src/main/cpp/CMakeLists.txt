cmake_minimum_required(VERSION 3.22.1)
project(aiostreams_exoplayer C)

# ass/ holds libass's public headers, 0.17.4 as libmpv carries it; the library is loaded from libmpv.
add_library(aiostreams_ass SHARED ass_surface.c)
target_compile_options(aiostreams_ass PRIVATE -O3 -Wall)
target_link_libraries(aiostreams_ass PRIVATE android dl log)

# ffmpeg/ holds FFmpeg n9.0.2's public headers (avcodec 63, avutil 61, swscale 10), as mpvlib 1.1.3
# carries it; the libraries are loaded from libmpv's at run time and sw_video.c refuses other majors.
add_library(nuvio_swvideo SHARED sw_video.c)
target_include_directories(nuvio_swvideo PRIVATE ffmpeg)
target_compile_options(nuvio_swvideo PRIVATE -O3 -Wall)
target_link_libraries(nuvio_swvideo PRIVATE android dl log)
