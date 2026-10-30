#ifndef GALAXY_QNX_ABI_CANDIDATE
#error Reconstructed declarations require explicit candidate build
#endif
/* SPDX-License-Identifier: GPL-3.0-only
 * 重建ABI诊断专用Screen声明；属性数值见本机静态getter及官方文档。
 * 不是SDK头文件，不进入Android或qcc正式include路径。
 */
#ifndef GALAXY_TEST_SCREEN_H
#define GALAXY_TEST_SCREEN_H
typedef void *screen_context_t;
typedef void *screen_display_t;
typedef void *screen_window_t;
typedef void *screen_buffer_t;
enum {
    SCREEN_APPLICATION_CONTEXT = 0, SCREEN_USAGE_WRITE = 4,
    SCREEN_FORMAT_RGBX8888 = 9, SCREEN_SENSITIVITY_NEVER = 2,
    SCREEN_PROPERTY_BUFFER_SIZE = 5, SCREEN_PROPERTY_DISPLAY = 11,
    SCREEN_PROPERTY_FORMAT = 14, SCREEN_PROPERTY_POINTER = 34,
    SCREEN_PROPERTY_RENDER_BUFFERS = 37, SCREEN_PROPERTY_SIZE = 40,
    SCREEN_PROPERTY_STRIDE = 44, SCREEN_PROPERTY_USAGE = 48,
    SCREEN_PROPERTY_VISIBLE = 51, SCREEN_PROPERTY_RENDER_BUFFER_COUNT = 53,
    SCREEN_PROPERTY_ZORDER = 54, SCREEN_PROPERTY_SENSITIVITY = 57,
    SCREEN_PROPERTY_DISPLAY_COUNT = 59, SCREEN_PROPERTY_DISPLAYS = 60,
    SCREEN_PROPERTY_ID = 87
};
int screen_create_context(screen_context_t *, int);
int screen_destroy_context(screen_context_t);
int screen_flush_context(screen_context_t, int);
int screen_create_window(screen_window_t *, screen_context_t);
int screen_destroy_window(screen_window_t);
int screen_create_window_buffers(screen_window_t, int);
int screen_set_window_property_iv(screen_window_t, int, const int *);
int screen_set_window_property_pv(screen_window_t, int, void **);
int screen_get_context_property_iv(screen_context_t, int, int *);
int screen_get_context_property_pv(screen_context_t, int, void **);
int screen_get_display_property_iv(screen_display_t, int, int *);
int screen_get_window_property_iv(screen_window_t, int, int *);
int screen_get_window_property_pv(screen_window_t, int, void **);
int screen_get_buffer_property_iv(screen_buffer_t, int, int *);
int screen_get_buffer_property_pv(screen_buffer_t, int, void **);
int screen_post_window(screen_window_t, screen_buffer_t, int, const int *, int);
#endif
