#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "NovlNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

struct go_string { const char *str; long n; };

int wgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings) {
    LOGI("Novl tunnel starting on tun_fd: %d", tun_fd);
    return 1;
}

void wgTurnOff(int handle) {
    LOGI("Novl tunnel stopping for handle: %d", handle);
}

int wgGetSocketV4(int handle) {
    return -1;
}

int wgGetSocketV6(int handle) {
    return -1;
}

char *wgGetConfig(int handle) {
    return NULL;
}

char *wgVersion() {
    return strdup("0.1.0-novl");
}
