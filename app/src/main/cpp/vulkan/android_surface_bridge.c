// SPDX-License-Identifier: MIT
#include "android_surface_bridge.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <media/NdkImageReader.h>
#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define BRIDGE_MAGIC 0x46414231u /* FAB1, all protocol integers are network byte order */
#define QUERY_SIZE 1u
#define FRAME 2u
#define MAX_EDGE 4096u

struct AndroidSurfaceBridge {
    AImageReader *reader;
    ANativeWindow *window; /* borrowed from reader, lives until AImageReader_delete */
    VkSurfaceKHR surface;
    int fd;
    uint32_t width, height;
    pthread_mutex_t io_lock;
    struct AndroidSurfaceBridge *next;
};

static pthread_mutex_t bridges_lock = PTHREAD_MUTEX_INITIALIZER;
static struct AndroidSurfaceBridge *bridges;

static int transfer(int fd, void *data, size_t bytes, int sending) {
    unsigned char *p = data;
    while (bytes) {
        ssize_t n = sending ? send(fd, p, bytes, MSG_NOSIGNAL) : recv(fd, p, bytes, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        p += n;
        bytes -= (size_t)n;
    }
    return 1;
}

static void disconnect(struct AndroidSurfaceBridge *b) {
    if (b->fd >= 0) { close(b->fd); b->fd = -1; }
}

static int read_size(struct AndroidSurfaceBridge *b) {
    uint32_t size[2];
    if (b->fd < 0 || !transfer(b->fd, size, sizeof(size), 0)) return 0;
    uint32_t w = ntohl(size[0]), h = ntohl(size[1]);
    if (!w || !h || w > MAX_EDGE || h > MAX_EDGE) return 0;
    b->width = w;
    b->height = h;
    return 1;
}

static void image_available(void *context, AImageReader *reader) {
    struct AndroidSurfaceBridge *b = context;
    AImage *image = NULL;
    if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK) return;
    int32_t width = 0, height = 0, row_stride = 0, pixel_stride = 0;
    uint8_t *pixels = NULL;
    int length = 0;
    if (AImage_getWidth(image, &width) != AMEDIA_OK ||
        AImage_getHeight(image, &height) != AMEDIA_OK ||
        AImage_getPlaneRowStride(image, 0, &row_stride) != AMEDIA_OK ||
        AImage_getPlanePixelStride(image, 0, &pixel_stride) != AMEDIA_OK ||
        AImage_getPlaneData(image, 0, &pixels, &length) != AMEDIA_OK ||
        width <= 0 || height <= 0 || (uint32_t)width > MAX_EDGE || (uint32_t)height > MAX_EDGE ||
        pixel_stride != 4 || row_stride < width * 4 ||
        (size_t)length < (size_t)(height - 1) * row_stride + (size_t)width * 4) {
        AImage_delete(image);
        return;
    }
    uint8_t *row = malloc((size_t)width * 4);
    if (!row) { AImage_delete(image); return; }
    pthread_mutex_lock(&b->io_lock);
    uint32_t header[] = { htonl(FRAME), htonl((uint32_t)width), htonl((uint32_t)height) };
    int ok = b->fd >= 0 && transfer(b->fd, header, sizeof(header), 1);
    for (int32_t y = 0; ok && y < height; ++y) {
        const uint8_t *src = pixels + (size_t)y * row_stride;
        /* Winlator's drawable/texture format is BGRA, not Android's RGBA. */
        for (int32_t x = 0; x < width; ++x) {
            row[x * 4] = src[x * 4 + 2];
            row[x * 4 + 1] = src[x * 4 + 1];
            row[x * 4 + 2] = src[x * 4];
            row[x * 4 + 3] = 255;
        }
        ok = transfer(b->fd, row, (size_t)width * 4, 1);
    }
    if (!ok) disconnect(b);
    pthread_mutex_unlock(&b->io_lock);
    free(row);
    AImage_delete(image); /* always release buffers, including on a broken connection */
}

struct AndroidSurfaceBridge *android_bridge_create(uint32_t xwindow) {
    const char *path = getenv("FABLE_VULKAN_SOCKET");
    if (!path || strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) return NULL;
    struct AndroidSurfaceBridge *b = calloc(1, sizeof(*b));
    if (!b) return NULL;
    pthread_mutex_init(&b->io_lock, NULL);
    b->fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (b->fd < 0) goto failed;
    /* Neither a missing display nor a stalled consumer may hang DXVK indefinitely. */
    struct timeval timeout = { .tv_sec = 5 };
    setsockopt(b->fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(b->fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    strcpy(address.sun_path, path);
    if (connect(b->fd, (struct sockaddr *)&address, sizeof(address)) != 0) goto failed;
    uint32_t hello[] = { htonl(BRIDGE_MAGIC), htonl(xwindow) };
    if (!transfer(b->fd, hello, sizeof(hello), 1) || !read_size(b)) goto failed;
    media_status_t status = AImageReader_newWithUsage(
        (int32_t)b->width, (int32_t)b->height, AIMAGE_FORMAT_RGBA_8888,
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
        3, &b->reader);
    if (status != AMEDIA_OK || AImageReader_getWindow(b->reader, &b->window) != AMEDIA_OK) goto failed;
    AImageReader_ImageListener listener = { .context = b, .onImageAvailable = image_available };
    if (AImageReader_setImageListener(b->reader, &listener) != AMEDIA_OK) goto failed;
    __android_log_print(ANDROID_LOG_INFO, "vulkan_shim", "X window %u -> Android BufferQueue %ux%u",
                        xwindow, b->width, b->height);
    return b;
failed:
    android_bridge_delete(b);
    return NULL;
}

ANativeWindow *android_bridge_window(struct AndroidSurfaceBridge *b) { return b->window; }

void android_bridge_attach(struct AndroidSurfaceBridge *b, VkSurfaceKHR surface) {
    pthread_mutex_lock(&bridges_lock);
    b->surface = surface;
    b->next = bridges;
    bridges = b;
    pthread_mutex_unlock(&bridges_lock);
}

void android_bridge_delete(struct AndroidSurfaceBridge *b) {
    if (!b) return;
    /* delete joins ImageReader's callback thread before we release its callback context.
     * Do not hold io_lock while joining: an in-flight callback may be using it. */
    if (b->reader) AImageReader_delete(b->reader);
    disconnect(b);
    pthread_mutex_destroy(&b->io_lock);
    free(b);
}

int android_bridge_contains(VkSurfaceKHR surface) {
    int found = 0;
    pthread_mutex_lock(&bridges_lock);
    for (struct AndroidSurfaceBridge *b = bridges; b; b = b->next) {
        if (b->surface == surface) { found = 1; break; }
    }
    pthread_mutex_unlock(&bridges_lock);
    return found;
}

int android_bridge_refresh(VkSurfaceKHR surface) {
    int ok = 1; /* unknown surfaces belong to the real loader */
    pthread_mutex_lock(&bridges_lock);
    for (struct AndroidSurfaceBridge *b = bridges; b; b = b->next) {
        if (b->surface != surface) continue;
        pthread_mutex_lock(&b->io_lock);
        uint32_t command = htonl(QUERY_SIZE);
        uint32_t old_w = b->width, old_h = b->height;
        ok = b->fd >= 0 && transfer(b->fd, &command, sizeof(command), 1) && read_size(b);
        if (ok && (old_w != b->width || old_h != b->height)) {
            ok = ANativeWindow_setBuffersGeometry(b->window, (int32_t)b->width,
                                                  (int32_t)b->height, 0) == 0;
        }
        if (!ok) disconnect(b);
        pthread_mutex_unlock(&b->io_lock);
        break;
    }
    pthread_mutex_unlock(&bridges_lock);
    return ok;
}

void android_bridge_destroy_surface(VkSurfaceKHR surface) {
    struct AndroidSurfaceBridge *b = NULL;
    pthread_mutex_lock(&bridges_lock);
    for (struct AndroidSurfaceBridge **p = &bridges; *p; p = &(*p)->next) {
        if ((*p)->surface == surface) { b = *p; *p = b->next; break; }
    }
    pthread_mutex_unlock(&bridges_lock);
    android_bridge_delete(b);
}
