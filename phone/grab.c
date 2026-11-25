// magicpad-grab: runs on the Android phone (as the adb `shell` user, no root).
//
// Finds the touchscreen evdev node, takes an exclusive grab (EVIOCGRAB) so
// Android itself stops reacting to touches, and streams raw input_event
// structs to stdout. The Mac side reads them over `adb exec-out`.
//
// Wire format:
//   header: "MPAD" + int32 version, xmax, ymax, majormax, slots
//   then raw `struct input_event` (24 bytes on arm64), unmodified.
//   A heartbeat event (type 0xFFFF) is written every second while idle so a
//   dead connection is noticed (write fails -> exit -> grab released).

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define VERSION 1
#define HEARTBEAT_TYPE 0xFFFF

static int write_all(const void *buf, size_t len) {
    const char *p = buf;
    while (len > 0) {
        ssize_t n = write(STDOUT_FILENO, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static int has_mt(int fd) {
    unsigned long abs_bits[(ABS_MAX + 1) / (8 * sizeof(long)) + 1] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) return 0;
    int bits = 8 * sizeof(long);
    return (abs_bits[ABS_MT_POSITION_X / bits] >> (ABS_MT_POSITION_X % bits)) & 1;
}

// Open the device whose name matches `want`, or the first multitouch device.
static int open_touchscreen(const char *want, char *path_out, size_t path_len) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;
    struct dirent *de;
    int fallback = -1;
    while ((de = readdir(dir)) != NULL) {
        if (strncmp(de->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", de->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        char name[128] = {0};
        ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
        if (want && strcmp(name, want) == 0) {
            if (fallback >= 0) close(fallback);
            closedir(dir);
            snprintf(path_out, path_len, "%s", path);
            return fd;
        }
        if (fallback < 0 && has_mt(fd) && strstr(name, "touchscreen")) {
            fallback = fd;
            snprintf(path_out, path_len, "%s", path);
        } else {
            close(fd);
        }
    }
    closedir(dir);
    return fallback;
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_DFL);  // a broken pipe kills us, which releases the grab
    const char *want = argc > 1 ? argv[1] : "sec_touchscreen";
    int grab = !(argc > 2 && strcmp(argv[2], "--no-grab") == 0);

    char path[64] = {0};
    int fd = open_touchscreen(want, path, sizeof(path));
    if (fd < 0) {
        fprintf(stderr, "magicpad-grab: no touchscreen found\n");
        return 1;
    }

    struct input_absinfo ax = {0}, ay = {0}, am = {0}, as = {0};
    ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &ax);
    ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &ay);
    ioctl(fd, EVIOCGABS(ABS_MT_TOUCH_MAJOR), &am);
    ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &as);

    if (grab && ioctl(fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "magicpad-grab: EVIOCGRAB failed (%s), continuing shared\n",
                strerror(errno));
    }
    fprintf(stderr, "magicpad-grab: streaming %s (grab=%d)\n", path, grab);

    int32_t header[5] = {VERSION, ax.maximum, ay.maximum, am.maximum, as.maximum + 1};
    if (write_all("MPAD", 4) < 0 || write_all(header, sizeof(header)) < 0) return 1;

    struct input_event buf[64];
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    for (;;) {
        int r = poll(&pfd, 1, 1000);
        if (r < 0 && errno == EINTR) continue;
        if (r == 0) {
            struct input_event hb = {0};
            hb.type = HEARTBEAT_TYPE;
            if (write_all(&hb, sizeof(hb)) < 0) break;
            continue;
        }
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break;
        }
        if (write_all(buf, (size_t)n) < 0) break;
    }
    ioctl(fd, EVIOCGRAB, 0);
    return 0;
}
