#include <jni.h>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstring>
#include <unistd.h>
#include <poll.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/mman.h>
#include <pthread.h>
#include <sched.h>
#include <algorithm>
#include <linux/usbdevice_fs.h>
#include "ring_buffer.h"

#define LOG_TAG "UsbDacNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif

#ifndef USB_SPEED_FULL
#define USB_SPEED_FULL 2
#define USB_SPEED_HIGH 3
#define USB_SPEED_SUPER 5
#endif

static const size_t RING_BUFFER_SIZE = 256 * 1024; // 256 KB per stream
static const size_t MAX_FIR_TAIL = 64;
static const int MAX_CHANNELS = 8;
static const int NUM_STREAMS = 2;

struct NativeAudioStream {
    LockFreeRingBuffer ringBuffer{RING_BUFFER_SIZE};
    std::atomic<bool> isPaused{true};
    std::atomic<int64_t> playedFrames{0};
    std::atomic<float> volumeScale{1.0f};
    std::atomic<int> sourceSampleRate{44100};
    std::atomic<int> sourceChannels{2};

    // Per-stream DSP state (protected by stream writer thread)
    float firTail[MAX_CHANNELS][MAX_FIR_TAIL];
    size_t firTailCount{0};
    float rsWin[2][4];
    int rsWinCount{0};
    double rsFrac{0.0};

    void resetDsp() {
        firTailCount = 0;
        memset(firTail, 0, sizeof(firTail));
        rsWinCount = 0;
        rsFrac = 0.0;
        memset(rsWin, 0, sizeof(rsWin));
    }

    void resetBufferAndDsp() {
        ringBuffer.reset();
        resetDsp();
        playedFrames.store(0, std::memory_order_relaxed);
    }

    void resetAll() {
        isPaused.store(true, std::memory_order_release);
        volumeScale.store(1.0f, std::memory_order_relaxed);
        resetBufferAndDsp();
    }
};

static NativeAudioStream g_streams[NUM_STREAMS];

static std::mutex g_lifecycleMutex;
static std::mutex g_urbCancelMutex;
static std::vector<struct usbdevfs_urb*> g_activeUrbs;
static int g_activeUrbFd = -1;

static std::atomic<bool> g_isRunning{false};
static std::atomic<float> g_masterVolumeScale{1.0f};

static std::thread g_workerThread;
static std::atomic<int> g_nativeFd{-1};
static std::atomic<int> g_epOut{0};
static std::atomic<int> g_maxPacketSize{1024};
static std::atomic<int> g_epInterval{1};
static std::atomic<int> g_sampleRate{44100};
static std::atomic<int> g_subframeSize{2}; // 2 = 16-bit, 3 = 24-bit packed, 4 = 32-bit slot
static std::atomic<int> g_channels{2};     // Always 2ch stereo to USB DAC

// ---------------------------------------------------------------------------
// Real-time Sample Scaling & Dual-Stream Crossfade Mixing Helpers
// ---------------------------------------------------------------------------

static inline int32_t read_container_sample(const uint8_t* p, int sf) {
    if (sf == 2) {
        return *reinterpret_cast<const int16_t*>(p);
    } else if (sf == 4) {
        return (*reinterpret_cast<const int32_t*>(p)) >> 8;
    } else {
        uint32_t u = static_cast<uint32_t>(p[0]) |
                    (static_cast<uint32_t>(p[1]) << 8) |
                    (static_cast<uint32_t>(p[2]) << 16);
        if (u & 0x00800000) u |= 0xFF000000;
        return static_cast<int32_t>(u);
    }
}

static inline void write_container_sample(uint8_t* p, int32_t val, int sf) {
    if (sf == 2) {
        int16_t s16 = static_cast<int16_t>(std::clamp(val, -32768, 32767));
        *reinterpret_cast<int16_t*>(p) = s16;
    } else if (sf == 4) {
        int32_t s24 = std::clamp(val, -8388608, 8388607);
        *reinterpret_cast<int32_t*>(p) = (s24 << 8);
    } else {
        int32_t s24 = std::clamp(val, -8388608, 8388607);
        p[0] = static_cast<uint8_t>(s24 & 0xFF);
        p[1] = static_cast<uint8_t>((s24 >> 8) & 0xFF);
        p[2] = static_cast<uint8_t>((s24 >> 16) & 0xFF);
    }
}

static inline void scale_buffer_in_place(uint8_t* buf, size_t bytes, int sf, float gain) {
    size_t numSamples = bytes / static_cast<size_t>(sf);
    for (size_t i = 0; i < numSamples; ++i) {
        uint8_t* p = buf + i * sf;
        int32_t s = read_container_sample(p, sf);
        int32_t scaled = static_cast<int32_t>(s * gain);
        write_container_sample(p, scaled, sf);
    }
}

static inline void mix_two_buffers(
    uint8_t* dst,
    const uint8_t* buf0,
    const uint8_t* buf1,
    size_t bytes,
    int sf,
    float gain0,
    float gain1
) {
    size_t numSamples = bytes / static_cast<size_t>(sf);
    for (size_t i = 0; i < numSamples; ++i) {
        int32_t s0 = read_container_sample(buf0 + i * sf, sf);
        int32_t s1 = read_container_sample(buf1 + i * sf, sf);
        int32_t mixed = static_cast<int32_t>(s0 * gain0 + s1 * gain1);
        write_container_sample(dst + i * sf, mixed, sf);
    }
}

// ---------------------------------------------------------------------------
// High-performance asynchronous Isochronous USB Audio Engine
// ---------------------------------------------------------------------------

struct UrbSlot {
    struct usbdevfs_urb* urb{nullptr};
    std::vector<uint8_t> urb_mem;
    std::vector<uint8_t> data_mem;
    bool in_flight{false};
};

static void fill_urb_packets(
    UrbSlot* slot,
    int num_packets,
    int fps,
    int maxPkt,
    uint32_t& remainder_acc
) {
    int sr = g_sampleRate.load(std::memory_order_relaxed);
    int sf = g_subframeSize.load(std::memory_order_relaxed);
    int ch = 2;
    if (sr <= 0) sr = 44100;
    if (sf < 2 || sf > 4) sf = 2;

    int bytesPerSample = sf;
    int bytesPerFrame = ch * bytesPerSample;

    uint32_t base_frames = static_cast<uint32_t>(sr / fps);
    uint32_t remainder = static_cast<uint32_t>(sr % fps);

    float masterVol = g_masterVolumeScale.load(std::memory_order_relaxed);
    bool active0 = !g_streams[0].isPaused.load(std::memory_order_acquire) &&
                   g_streams[0].ringBuffer.available() > 0;
    bool active1 = !g_streams[1].isPaused.load(std::memory_order_acquire) &&
                   g_streams[1].ringBuffer.available() > 0;

    float vol0 = masterVol * g_streams[0].volumeScale.load(std::memory_order_relaxed);
    float vol1 = masterVol * g_streams[1].volumeScale.load(std::memory_order_relaxed);

    uint8_t mixScratch0[3072];
    uint8_t mixScratch1[3072];
    size_t offset = 0;

    for (int p = 0; p < num_packets; ++p) {
        uint32_t frames = base_frames;
        remainder_acc += remainder;
        if (remainder_acc >= static_cast<uint32_t>(fps)) {
            frames++;
            remainder_acc -= static_cast<uint32_t>(fps);
        }

        size_t pkt_len = frames * bytesPerFrame;
        if (pkt_len > static_cast<size_t>(maxPkt)) {
            frames = static_cast<uint32_t>(maxPkt / bytesPerFrame);
            pkt_len = frames * bytesPerFrame;
        }
        if (pkt_len > sizeof(mixScratch0)) {
            frames = static_cast<uint32_t>(sizeof(mixScratch0) / bytesPerFrame);
            pkt_len = frames * bytesPerFrame;
        }

        uint8_t* pkt_dest = slot->data_mem.data() + offset;

        if (!active0 && !active1) {
            memset(pkt_dest, 0, pkt_len);
        } else if (active0 && !active1) {
            size_t read_bytes = g_streams[0].ringBuffer.read(pkt_dest, pkt_len);
            if (read_bytes > 0) {
                g_streams[0].playedFrames.fetch_add(read_bytes / bytesPerFrame, std::memory_order_relaxed);
                if (vol0 < 0.999f) {
                    scale_buffer_in_place(pkt_dest, read_bytes, sf, vol0);
                }
            }
            if (g_streams[0].ringBuffer.available() == 0) active0 = false;
        } else if (!active0 && active1) {
            size_t read_bytes = g_streams[1].ringBuffer.read(pkt_dest, pkt_len);
            if (read_bytes > 0) {
                g_streams[1].playedFrames.fetch_add(read_bytes / bytesPerFrame, std::memory_order_relaxed);
                if (vol1 < 0.999f) {
                    scale_buffer_in_place(pkt_dest, read_bytes, sf, vol1);
                }
            }
            if (g_streams[1].ringBuffer.available() == 0) active1 = false;
        } else {
            // Both Stream 0 and Stream 1 active simultaneously (Real-time Crossfade Mixing!)
            size_t read0 = g_streams[0].ringBuffer.read(mixScratch0, pkt_len);
            size_t read1 = g_streams[1].ringBuffer.read(mixScratch1, pkt_len);
            if (read0 > 0) {
                g_streams[0].playedFrames.fetch_add(read0 / bytesPerFrame, std::memory_order_relaxed);
            }
            if (read1 > 0) {
                g_streams[1].playedFrames.fetch_add(read1 / bytesPerFrame, std::memory_order_relaxed);
            }
            mix_two_buffers(pkt_dest, mixScratch0, mixScratch1, pkt_len, sf, vol0, vol1);
            if (g_streams[0].ringBuffer.available() == 0) active0 = false;
            if (g_streams[1].ringBuffer.available() == 0) active1 = false;
        }

        slot->urb->iso_frame_desc[p].length = pkt_len;
        slot->urb->iso_frame_desc[p].actual_length = 0;
        slot->urb->iso_frame_desc[p].status = 0;
        offset += pkt_len;
    }
    slot->urb->buffer_length = static_cast<int>(offset);
    slot->urb->status = 0;
    slot->urb->actual_length = 0;
    slot->urb->error_count = 0;
    slot->urb->start_frame = 0;
}

static void audio_transfer_loop() {
    pthread_setname_np(pthread_self(), "YoPlayerIso");

    // Real-time priorities
    prctl(PR_SET_TIMERSLACK, 1, 0, 0, 0);
    setpriority(PRIO_PROCESS, 0, -20);
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    if (param.sched_priority > 2) param.sched_priority = 2;
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);

    long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (num_cpus > 0) {
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        for (int c = 0; c < num_cpus; c++) CPU_SET(c, &cpuset);
        sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
    }

    int fd = g_nativeFd.load(std::memory_order_acquire);
    int ep = g_epOut.load(std::memory_order_acquire);
    int maxPkt = g_maxPacketSize.load(std::memory_order_acquire);
    if (maxPkt <= 0 || maxPkt > 3072) maxPkt = 1024;
    int epInterval = g_epInterval.load(std::memory_order_acquire);

    if (fd < 0) {
        g_isRunning.store(false, std::memory_order_release);
        return;
    }

    // Determine USB bus speed & microframe frequency
    int speed = ioctl(fd, USBDEVFS_GET_SPEED, 0);

    bool is_high_speed = false;
    if (speed == USB_SPEED_HIGH || speed == USB_SPEED_SUPER) {
        is_high_speed = true;
    } else if (speed == USB_SPEED_FULL) {
        is_high_speed = false;
    } else {
        is_high_speed = (maxPkt > 1024);
    }

    int fps = 1000;
    if (is_high_speed) {
        int intervalShift = std::clamp(epInterval - 1, 0, 3);
        fps = std::max(1000, 8000 >> intervalShift);
    }
    int num_packets = (fps >= 4000) ? 32 : 8; // 4 ms (High-Speed) or 8 ms (Full-Speed)
    const int NUM_URBS = 16;                  // 16 in-flight URBs = 64-128 ms jitter-free queue

    LOGI("ISO Thread started: FD=%d, EP=0x%02X, speed=%d, fps=%d, num_pkts=%d, maxPkt=%d",
         fd, ep, speed, fps, num_packets, maxPkt);

    std::vector<UrbSlot> slots(NUM_URBS);
    size_t urb_hdr_size = sizeof(struct usbdevfs_urb) + num_packets * sizeof(struct usbdevfs_iso_packet_desc);
    size_t data_buf_size = num_packets * maxPkt;

    {
        std::lock_guard<std::mutex> cancelLock(g_urbCancelMutex);
        g_activeUrbs.clear();
        g_activeUrbFd = fd;
        for (int i = 0; i < NUM_URBS; ++i) {
            slots[i].urb_mem.resize(urb_hdr_size, 0);
            slots[i].data_mem.resize(data_buf_size, 0);
            slots[i].in_flight = false;

            mlock(slots[i].urb_mem.data(), urb_hdr_size);
            mlock(slots[i].data_mem.data(), data_buf_size);

            struct usbdevfs_urb* u = reinterpret_cast<struct usbdevfs_urb*>(slots[i].urb_mem.data());
            slots[i].urb = u;

            u->type = USBDEVFS_URB_TYPE_ISO;
            u->flags = USBDEVFS_URB_ISO_ASAP;
            u->endpoint = static_cast<unsigned char>(ep & 0xFF);
            u->buffer = slots[i].data_mem.data();
            u->number_of_packets = num_packets;
            u->usercontext = &slots[i];
            g_activeUrbs.push_back(u);
        }
    }

    uint32_t remainder_acc = 0;

    int in_flight_count = 0;
    for (int i = 0; i < NUM_URBS; ++i) {
        fill_urb_packets(&slots[i], num_packets, fps, maxPkt, remainder_acc);
        int ret = ioctl(fd, USBDEVFS_SUBMITURB, slots[i].urb);
        if (ret == 0) {
            slots[i].in_flight = true;
            in_flight_count++;
        } else {
            LOGW("Initial SUBMITURB[%d] failed: errno=%d", i, errno);
        }
    }

    LOGI("ISO queue primed: %d/%d URBs submitted to kernel", in_flight_count, NUM_URBS);

    while (g_isRunning.load(std::memory_order_acquire) && in_flight_count > 0) {
        struct usbdevfs_urb* reaped = nullptr;
        int ret = ioctl(fd, USBDEVFS_REAPURB, &reaped);
        if (!g_isRunning.load(std::memory_order_acquire)) {
            break;
        }
        if (ret < 0) {
            int err = errno;
            if (err == EINTR || err == EAGAIN) continue;
            if (err == ENODEV || err == ENOENT || err == EBADF) {
                LOGE("REAPURB: stopping (errno=%d)", err);
                break;
            }
            if (err == ESHUTDOWN) {
                usleep(5000);
                continue;
            }
            struct pollfd pfd = { fd, POLLOUT, 0 };
            poll(&pfd, 1, 10);
            continue;
        }

        if (!reaped) continue;

        UrbSlot* slot = reinterpret_cast<UrbSlot*>(reaped->usercontext);
        if (!slot) continue;
        if (slot->in_flight) {
            slot->in_flight = false;
            in_flight_count--;
        }

        if (!g_isRunning.load(std::memory_order_relaxed)) {
            break;
        }

        fill_urb_packets(slot, num_packets, fps, maxPkt, remainder_acc);

        int retries = 0;
        while (g_isRunning.load(std::memory_order_relaxed)) {
            ret = ioctl(fd, USBDEVFS_SUBMITURB, slot->urb);
            if (ret == 0) {
                slot->in_flight = true;
                in_flight_count++;
                break;
            }
            int err = errno;
            if (err == ENODEV || err == ENOENT || err == EBADF) {
                LOGE("SUBMITURB: stopping (errno=%d)", err);
                goto cleanup;
            }
            if (err == ESHUTDOWN || err == EPIPE || err == EPROTO) {
                usleep(5000);
                retries++;
                if (retries > 20) {
                    LOGW("SUBMITURB retry timeout (errno=%d)", err);
                    break;
                }
                continue;
            }
            LOGW("SUBMITURB transient error: errno=%d", err);
            break;
        }
    }

cleanup:
    g_isRunning.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> cancelLock(g_urbCancelMutex);
        g_activeUrbs.clear();
        g_activeUrbFd = -1;
    }
    for (int i = 0; i < NUM_URBS; ++i) {
        ioctl(fd, USBDEVFS_DISCARDURB, slots[i].urb);
    }
    for (int i = 0; i < NUM_URBS * 2; ++i) {
        struct usbdevfs_urb* reaped = nullptr;
        if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped) < 0) {
            break;
        }
    }
    for (int i = 0; i < NUM_URBS; ++i) {
        munlock(slots[i].urb_mem.data(), slots[i].urb_mem.size());
        munlock(slots[i].data_mem.data(), slots[i].data_mem.size());
    }

    LOGI("ISO audio transfer thread stopped");
}

// ---------------------------------------------------------------------------
// High-Precision Studio FIR Decimation Filter Tables
// Kaiser windowed sinc (beta = 7.865), -80 dB stopband attenuation, linear phase
// ---------------------------------------------------------------------------

static const float FIR_TAPS_4TO1[31] = {
    -0.00004556f, -0.00006357f, +0.00029280f, +0.00135116f,
    +0.00276664f, +0.00294450f, -0.00051754f, -0.00884554f,
    -0.01938110f, -0.02431059f, -0.01293811f, +0.02246406f,
    +0.08017712f, +0.14643992f, +0.19964580f, +0.22004005f,
    +0.19964580f, +0.14643992f, +0.08017712f, +0.02246406f,
    -0.01293811f, -0.02431059f, -0.01938110f, -0.00884554f,
    -0.00051754f, +0.00294450f, +0.00276664f, +0.00135116f,
    +0.00029280f, -0.00006357f, -0.00004556f
};

static const float FIR_TAPS_2TO1[25] = {
    -0.00005423f, +0.00020290f, +0.00122825f, -0.00038400f,
    -0.00624833f, -0.00287810f, +0.01803589f, +0.01923018f,
    -0.03581962f, -0.07096530f, +0.05287022f, +0.30478301f,
    +0.43999827f, +0.30478301f, +0.05287022f, -0.07096530f,
    -0.03581962f, +0.01923018f, +0.01803589f, -0.00287810f,
    -0.00624833f, -0.00038400f, +0.00122825f, +0.00020290f,
    -0.00005423f
};

static const float FIR_TAPS_8TO1[31] = {
    -0.00005295f, -0.00026760f, -0.00070822f, -0.00133050f,
    -0.00184746f, -0.00163357f, +0.00027321f, +0.00501972f,
    +0.01363284f, +0.02662588f, +0.04363871f, +0.06325510f,
    +0.08310571f, +0.10027968f, +0.11195903f, +0.11610082f,
    +0.11195903f, +0.10027968f, +0.08310571f, +0.06325510f,
    +0.04363871f, +0.02662588f, +0.01363284f, +0.00501972f,
    +0.00027321f, -0.00163357f, -0.00184746f, -0.00133050f,
    -0.00070822f, -0.00026760f, -0.00005295f
};

static inline float decode_pcm_sample(const uint8_t* p, int enc) {
    if (enc == 2) {
        return (*reinterpret_cast<const int16_t*>(p)) * (1.0f / 32768.0f);
    } else if (enc == 21 || enc == 0x20000000) {
        uint32_t u = static_cast<uint32_t>(p[0]) |
                    (static_cast<uint32_t>(p[1]) << 8) |
                    (static_cast<uint32_t>(p[2]) << 16);
        if (u & 0x00800000) u |= 0xFF000000;
        return static_cast<int32_t>(u) * (1.0f / 8388608.0f);
    } else if (enc == 4) {
        return *reinterpret_cast<const float*>(p);
    } else if (enc == 22 || enc == 0x30000000) {
        return (*reinterpret_cast<const int32_t*>(p)) * (1.0f / 2147483648.0f);
    }
    return 0.0f;
}

static inline size_t get_pcm_bytes_per_sample(int enc) {
    if (enc == 2) return 2;
    if (enc == 4 || enc == 22 || enc == 0x30000000) return 4;
    return 3; // 24-bit packed
}

static size_t process_fir_decimation(
    NativeAudioStream& stream,
    const uint8_t* src,
    size_t length,
    int pcmEncoding,
    int sf,
    int ch,
    int ratio
) {
    size_t srcBps = get_pcm_bytes_per_sample(pcmEncoding);
    size_t srcBytesPerFrame = ch * srcBps;
    size_t newSrcFrames = length / srcBytesPerFrame;
    if (newSrcFrames == 0) return 0;

    const float* taps = nullptr;
    int numTaps = 0;
    if (ratio == 4) {
        taps = FIR_TAPS_4TO1;
        numTaps = 31;
    } else if (ratio == 2) {
        taps = FIR_TAPS_2TO1;
        numTaps = 25;
    } else if (ratio == 8) {
        taps = FIR_TAPS_8TO1;
        numTaps = 31;
    } else {
        taps = nullptr;
        numTaps = ratio;
    }

    size_t totalAvail = stream.firTailCount + newSrcFrames;
    if (totalAvail < static_cast<size_t>(numTaps)) {
        for (size_t f = 0; f < newSrcFrames && (stream.firTailCount + f) < MAX_FIR_TAIL; ++f) {
            const uint8_t* fptr = src + f * srcBytesPerFrame;
            for (int c = 0; c < ch && c < MAX_CHANNELS; ++c) {
                stream.firTail[c][stream.firTailCount + f] = decode_pcm_sample(fptr + c * srcBps, pcmEncoding);
            }
        }
        stream.firTailCount += newSrcFrames;
        return length;
    }

    size_t totalOutFrames = (totalAvail - numTaps) / ratio + 1;
    const size_t CHUNK_OUT_FRAMES = 256;
    size_t outFramesWritten = 0;
    uint8_t outChunkBuf[CHUNK_OUT_FRAMES * 8 * 4];
    size_t outBytesPerFrame = ch * sf;

    while (outFramesWritten < totalOutFrames) {
        size_t chunkFrames = std::min(totalOutFrames - outFramesWritten, CHUNK_OUT_FRAMES);
        for (size_t f = 0; f < chunkFrames; ++f) {
            size_t outIdx = outFramesWritten + f;
            size_t inStart = outIdx * ratio;

            for (int c = 0; c < ch && c < MAX_CHANNELS; ++c) {
                float acc = 0.0f;
                if (taps != nullptr) {
                    for (int k = 0; k < numTaps; ++k) {
                        size_t sampleIdx = inStart + k;
                        float val;
                        if (sampleIdx < stream.firTailCount) {
                            val = stream.firTail[c][sampleIdx];
                        } else {
                            size_t srcFrameIdx = sampleIdx - stream.firTailCount;
                            const uint8_t* sptr = src + srcFrameIdx * srcBytesPerFrame + c * srcBps;
                            val = decode_pcm_sample(sptr, pcmEncoding);
                        }
                        acc += taps[k] * val;
                    }
                } else {
                    for (int k = 0; k < ratio; ++k) {
                        size_t sampleIdx = inStart + k;
                        float val;
                        if (sampleIdx < stream.firTailCount) {
                            val = stream.firTail[c][sampleIdx];
                        } else {
                            size_t srcFrameIdx = sampleIdx - stream.firTailCount;
                            const uint8_t* sptr = src + srcFrameIdx * srcBytesPerFrame + c * srcBps;
                            val = decode_pcm_sample(sptr, pcmEncoding);
                        }
                        acc += val;
                    }
                    acc /= static_cast<float>(ratio);
                }

                acc = std::clamp(acc, -1.0f, 1.0f);

                if (sf == 2) {
                    int16_t s16 = static_cast<int16_t>(std::clamp(acc * 32767.0f, -32768.0f, 32767.0f));
                    int16_t* p = reinterpret_cast<int16_t*>(outChunkBuf + (f * ch + c) * 2);
                    *p = s16;
                } else if (sf == 4) {
                    int32_t s24 = static_cast<int32_t>(std::clamp(acc * 8388607.0f, -8388608.0f, 8388607.0f));
                    int32_t s32 = s24 << 8;
                    int32_t* p = reinterpret_cast<int32_t*>(outChunkBuf + (f * ch + c) * 4);
                    *p = s32;
                } else {
                    int32_t s24 = static_cast<int32_t>(std::clamp(acc * 8388607.0f, -8388608.0f, 8388607.0f));
                    uint8_t* p = outChunkBuf + (f * ch + c) * 3;
                    p[0] = static_cast<uint8_t>(s24 & 0xFF);
                    p[1] = static_cast<uint8_t>((s24 >> 8) & 0xFF);
                    p[2] = static_cast<uint8_t>((s24 >> 16) & 0xFF);
                }
            }
        }

        size_t chunkBytes = chunkFrames * outBytesPerFrame;
        size_t written = stream.ringBuffer.write(outChunkBuf, chunkBytes);
        size_t fw = written / outBytesPerFrame;
        outFramesWritten += fw;
        if (fw < chunkFrames) {
            break;
        }
    }

    const size_t oldTailCount = stream.firTailCount;
    size_t inFramesConsumed = outFramesWritten * ratio;

    if (outFramesWritten == totalOutFrames) {
        size_t tailRemaining = (totalAvail > inFramesConsumed) ? (totalAvail - inFramesConsumed) : 0;
        if (tailRemaining > MAX_FIR_TAIL) tailRemaining = MAX_FIR_TAIL;

        float tempTail[MAX_CHANNELS][MAX_FIR_TAIL];
        for (size_t i = 0; i < tailRemaining; ++i) {
            size_t sampleIdx = inFramesConsumed + i;
            for (int c = 0; c < ch && c < MAX_CHANNELS; ++c) {
                if (sampleIdx < oldTailCount) {
                    tempTail[c][i] = stream.firTail[c][sampleIdx];
                } else {
                    size_t srcFrameIdx = sampleIdx - oldTailCount;
                    const uint8_t* sptr = src + srcFrameIdx * srcBytesPerFrame + c * srcBps;
                    tempTail[c][i] = decode_pcm_sample(sptr, pcmEncoding);
                }
            }
        }
        for (int c = 0; c < ch && c < MAX_CHANNELS; ++c) {
            for (size_t i = 0; i < tailRemaining; ++i) {
                stream.firTail[c][i] = tempTail[c][i];
            }
        }
        stream.firTailCount = tailRemaining;
        return length;
    } else {
        if (inFramesConsumed >= oldTailCount) {
            stream.firTailCount = 0;
            size_t consumedSrcFrames = std::min(inFramesConsumed - oldTailCount, newSrcFrames);
            return consumedSrcFrames * srcBytesPerFrame;
        } else {
            size_t tailRemaining = oldTailCount - inFramesConsumed;
            for (int c = 0; c < ch && c < MAX_CHANNELS; ++c) {
                for (size_t i = 0; i < tailRemaining; ++i) {
                    stream.firTail[c][i] = stream.firTail[c][inFramesConsumed + i];
                }
            }
            stream.firTailCount = tailRemaining;
            return 0;
        }
    }
}

// ---------------------------------------------------------------------------
// Universal Channel Downmix/Upmix (Mono / 5.1 / 7.1 / N-ch -> Stereo)
// & Arbitrary-Ratio Cubic Hermite (Catmull-Rom) Streaming Resampler
// ---------------------------------------------------------------------------

static inline void decode_frame_to_stereo(
    const uint8_t* fptr,
    size_t srcBps,
    int pcmEncoding,
    int srcCh,
    float& outL,
    float& outR
) {
    if (srcCh == 2) {
        outL = decode_pcm_sample(fptr, pcmEncoding);
        outR = decode_pcm_sample(fptr + srcBps, pcmEncoding);
        return;
    }
    if (srcCh == 1) {
        float m = decode_pcm_sample(fptr, pcmEncoding);
        outL = m;
        outR = m;
        return;
    }
    if (srcCh == 6) {
        constexpr float kSurround = 0.70710678f;
        float fl  = decode_pcm_sample(fptr + 0 * srcBps, pcmEncoding);
        float fr  = decode_pcm_sample(fptr + 1 * srcBps, pcmEncoding);
        float fc  = decode_pcm_sample(fptr + 2 * srcBps, pcmEncoding);
        float lfe = decode_pcm_sample(fptr + 3 * srcBps, pcmEncoding);
        float sl  = decode_pcm_sample(fptr + 4 * srcBps, pcmEncoding);
        float sr  = decode_pcm_sample(fptr + 5 * srcBps, pcmEncoding);
        outL = std::clamp(fl + kSurround * (fc + sl + lfe), -1.0f, 1.0f);
        outR = std::clamp(fr + kSurround * (fc + sr + lfe), -1.0f, 1.0f);
        return;
    }
    if (srcCh == 8) {
        constexpr float kSurround = 0.70710678f;
        float fl  = decode_pcm_sample(fptr + 0 * srcBps, pcmEncoding);
        float fr  = decode_pcm_sample(fptr + 1 * srcBps, pcmEncoding);
        float fc  = decode_pcm_sample(fptr + 2 * srcBps, pcmEncoding);
        float lfe = decode_pcm_sample(fptr + 3 * srcBps, pcmEncoding);
        float sl  = decode_pcm_sample(fptr + 4 * srcBps, pcmEncoding);
        float sr  = decode_pcm_sample(fptr + 5 * srcBps, pcmEncoding);
        float sbl = decode_pcm_sample(fptr + 6 * srcBps, pcmEncoding);
        float sbr = decode_pcm_sample(fptr + 7 * srcBps, pcmEncoding);
        outL = std::clamp(fl + kSurround * (fc + sl + sbl + lfe), -1.0f, 1.0f);
        outR = std::clamp(fr + kSurround * (fc + sr + sbr + lfe), -1.0f, 1.0f);
        return;
    }
    float sumL = 0.0f, sumR = 0.0f;
    int cntL = 0, cntR = 0;
    for (int c = 0; c < srcCh; ++c) {
        float s = decode_pcm_sample(fptr + c * srcBps, pcmEncoding);
        if (c % 2 == 0) { sumL += s; cntL++; }
        else            { sumR += s; cntR++; }
    }
    outL = std::clamp(cntL > 0 ? (sumL / static_cast<float>(cntL)) : 0.0f, -1.0f, 1.0f);
    outR = std::clamp(cntR > 0 ? (sumR / static_cast<float>(cntR)) : outL, -1.0f, 1.0f);
}

static inline float catmull_rom(float y0, float y1, float y2, float y3, float t) {
    float c0 = y1;
    float c1 = 0.5f * (y2 - y0);
    float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
    float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
    return ((c3 * t + c2) * t + c1) * t + c0;
}

static inline void pack_stereo_sample(uint8_t* dst, float l, float r, int sf) {
    if (sf == 2) {
        int16_t* p = reinterpret_cast<int16_t*>(dst);
        p[0] = static_cast<int16_t>(std::clamp(l * 32767.0f, -32768.0f, 32767.0f));
        p[1] = static_cast<int16_t>(std::clamp(r * 32767.0f, -32768.0f, 32767.0f));
    } else if (sf == 4) {
        int32_t* p = reinterpret_cast<int32_t*>(dst);
        int32_t l24 = static_cast<int32_t>(std::clamp(l * 8388607.0f, -8388608.0f, 8388607.0f));
        int32_t r24 = static_cast<int32_t>(std::clamp(r * 8388607.0f, -8388608.0f, 8388607.0f));
        p[0] = l24 << 8;
        p[1] = r24 << 8;
    } else {
        int32_t l24 = static_cast<int32_t>(std::clamp(l * 8388607.0f, -8388608.0f, 8388607.0f));
        int32_t r24 = static_cast<int32_t>(std::clamp(r * 8388607.0f, -8388608.0f, 8388607.0f));
        dst[0] = static_cast<uint8_t>(l24 & 0xFF);
        dst[1] = static_cast<uint8_t>((l24 >> 8) & 0xFF);
        dst[2] = static_cast<uint8_t>((l24 >> 16) & 0xFF);
        dst[3] = static_cast<uint8_t>(r24 & 0xFF);
        dst[4] = static_cast<uint8_t>((r24 >> 8) & 0xFF);
        dst[5] = static_cast<uint8_t>((r24 >> 16) & 0xFF);
    }
}

static size_t process_universal_resample(
    NativeAudioStream& stream,
    const uint8_t* src,
    size_t length,
    int pcmEncoding,
    int sf,
    int srcCh,
    int srcRate,
    int targetRate
) {
    size_t srcBps = get_pcm_bytes_per_sample(pcmEncoding);
    size_t srcBytesPerFrame = static_cast<size_t>(srcCh) * srcBps;
    size_t newSrcFrames = length / srcBytesPerFrame;
    if (newSrcFrames == 0) return 0;

    size_t outBytesPerFrame = 2 * static_cast<size_t>(sf);
    size_t freeBytes = stream.ringBuffer.capacity() - stream.ringBuffer.available();
    size_t maxOutFrames = freeBytes / outBytesPerFrame;
    if (maxOutFrames == 0) return 0;

    const bool sameRate = (srcRate == targetRate);
    const double step = sameRate ? 1.0 : (static_cast<double>(srcRate) / static_cast<double>(targetRate));

    constexpr size_t CHUNK_OUT_FRAMES = 256;
    uint8_t outChunkBuf[CHUNK_OUT_FRAMES * 2 * 4];
    size_t chunkCount = 0;
    size_t outWritten = 0;
    size_t srcIdx = 0;

    while (outWritten < maxOutFrames) {
        while (stream.rsWinCount < 4 && srcIdx < newSrcFrames) {
            float l = 0.0f, r = 0.0f;
            decode_frame_to_stereo(src + srcIdx * srcBytesPerFrame, srcBps, pcmEncoding, srcCh, l, r);
            srcIdx++;
            if (stream.rsWinCount == 0) {
                stream.rsWin[0][0] = l; stream.rsWin[1][0] = r;
                stream.rsWin[0][1] = l; stream.rsWin[1][1] = r;
                stream.rsWinCount = 2;
            } else {
                stream.rsWin[0][stream.rsWinCount] = l;
                stream.rsWin[1][stream.rsWinCount] = r;
                stream.rsWinCount++;
            }
        }

        if (stream.rsWinCount < 4) {
            break;
        }

        while (stream.rsFrac < 1.0 && outWritten < maxOutFrames) {
            float outL, outR;
            if (sameRate) {
                outL = stream.rsWin[0][1];
                outR = stream.rsWin[1][1];
            } else {
                float t = static_cast<float>(stream.rsFrac);
                outL = catmull_rom(stream.rsWin[0][0], stream.rsWin[0][1], stream.rsWin[0][2], stream.rsWin[0][3], t);
                outR = catmull_rom(stream.rsWin[1][0], stream.rsWin[1][1], stream.rsWin[1][2], stream.rsWin[1][3], t);
            }
            outL = std::clamp(outL, -1.0f, 1.0f);
            outR = std::clamp(outR, -1.0f, 1.0f);

            pack_stereo_sample(outChunkBuf + chunkCount * outBytesPerFrame, outL, outR, sf);
            chunkCount++;
            outWritten++;

            if (chunkCount == CHUNK_OUT_FRAMES) {
                stream.ringBuffer.write(outChunkBuf, chunkCount * outBytesPerFrame);
                chunkCount = 0;
            }

            stream.rsFrac += step;
        }

        while (stream.rsFrac >= 1.0 && stream.rsWinCount > 0) {
            for (int i = 0; i < stream.rsWinCount - 1; ++i) {
                stream.rsWin[0][i] = stream.rsWin[0][i + 1];
                stream.rsWin[1][i] = stream.rsWin[1][i + 1];
            }
            stream.rsWinCount--;
            stream.rsFrac -= 1.0;
        }
    }

    if (chunkCount > 0) {
        stream.ringBuffer.write(outChunkBuf, chunkCount * outBytesPerFrame);
    }

    return srcIdx * srcBytesPerFrame;
}

// ---------------------------------------------------------------------------
// Per-Stream PCM Processing & Ingestion
// ---------------------------------------------------------------------------

static size_t process_and_write_pcm(
    int streamIdx,
    const uint8_t* src,
    size_t length,
    int pcmEncoding,
    int targetSubframeSize
) {
    if (!g_isRunning.load(std::memory_order_relaxed) || length == 0 || src == nullptr) {
        return 0;
    }
    int idx = (streamIdx == 1) ? 1 : 0;
    NativeAudioStream& stream = g_streams[idx];

    int sf = targetSubframeSize;
    if (sf > 4) {
        sf = (sf <= 16) ? 2 : 4;
    }
    if (sf < 2) sf = 2;

    int srcCh = stream.sourceChannels.load(std::memory_order_relaxed);
    if (srcCh <= 0) srcCh = 2;

    int srcRate = stream.sourceSampleRate.load(std::memory_order_relaxed);
    int targetRate = g_sampleRate.load(std::memory_order_relaxed);
    if (srcRate <= 0) srcRate = 44100;
    if (targetRate <= 0) targetRate = srcRate;

    int ratio = 1;
    if (srcRate >= targetRate * 2 && (srcRate % targetRate == 0)) {
        int r = srcRate / targetRate;
        if (r == 2 || r == 4 || r == 8) {
            ratio = r;
        }
    }

    // BRANCH A: Studio-Grade Anti-Aliased FIR Decimation (stereo, ratio == 2, 4, or 8)
    if (srcCh == 2 && ratio >= 2) {
        return process_fir_decimation(stream, src, length, pcmEncoding, sf, 2, ratio);
    }

    // BRANCH B: Universal Stereo Downmix/Upmix & Arbitrary-Ratio Cubic Resampler
    if (srcCh != 2 || srcRate != targetRate) {
        return process_universal_resample(stream, src, length, pcmEncoding, sf, srcCh, srcRate, targetRate);
    }

    // BRANCH C: Direct 1:1 Bit-Perfect Passthrough / Container Formatting (stereo, srcRate == targetRate)
    if (pcmEncoding == 2) {
        const int16_t* in16 = reinterpret_cast<const int16_t*>(src);
        size_t numSamples = length / sizeof(int16_t);

        if (sf == 2) {
            return stream.ringBuffer.write(src, length);
        } else if (sf == 4) {
            size_t samplesProcessed = 0;
            int32_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    tempBuf[i] = static_cast<int32_t>(in16[samplesProcessed + i]) << 16;
                }
                size_t written = stream.ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int32_t));
                size_t sw = written / sizeof(int32_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * sizeof(int16_t);
        } else {
            size_t samplesProcessed = 0;
            uint8_t tempBuf[3072];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    int16_t s16 = in16[samplesProcessed + i];
                    tempBuf[3 * i + 0] = 0;
                    tempBuf[3 * i + 1] = static_cast<uint8_t>(s16 & 0xFF);
                    tempBuf[3 * i + 2] = static_cast<uint8_t>((s16 >> 8) & 0xFF);
                }
                size_t written = stream.ringBuffer.write(tempBuf, chunk * 3);
                size_t sw = written / 3;
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * sizeof(int16_t);
        }
    } else if (pcmEncoding == 21 || pcmEncoding == 0x20000000) {
        size_t numSamples = length / 3;
        size_t samplesProcessed = 0;

        if (sf == 4) {
            int32_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    size_t srcIdx = 3 * (samplesProcessed + i);
                    uint32_t u24 = static_cast<uint32_t>(src[srcIdx + 0]) |
                                  (static_cast<uint32_t>(src[srcIdx + 1]) << 8) |
                                  (static_cast<uint32_t>(src[srcIdx + 2]) << 16);
                    if (u24 & 0x00800000) u24 |= 0xFF000000;
                    tempBuf[i] = static_cast<int32_t>(u24 << 8);
                }
                size_t written = stream.ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int32_t));
                size_t sw = written / sizeof(int32_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * 3;
        } else if (sf == 2) {
            int16_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    size_t srcIdx = 3 * (samplesProcessed + i);
                    uint32_t u24 = static_cast<uint32_t>(src[srcIdx + 0]) |
                                  (static_cast<uint32_t>(src[srcIdx + 1]) << 8) |
                                  (static_cast<uint32_t>(src[srcIdx + 2]) << 16);
                    if (u24 & 0x00800000) u24 |= 0xFF000000;
                    tempBuf[i] = static_cast<int16_t>(static_cast<int32_t>(u24) >> 8);
                }
                size_t written = stream.ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int16_t));
                size_t sw = written / sizeof(int16_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * 3;
        } else {
            return stream.ringBuffer.write(src, length);
        }
    } else if (pcmEncoding == 4) {
        const float* fsrc = reinterpret_cast<const float*>(src);
        size_t numSamples = length / sizeof(float);
        size_t samplesProcessed = 0;

        if (sf == 4) {
            int32_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    float v = std::clamp(fsrc[samplesProcessed + i], -1.0f, 1.0f);
                    int32_t s24 = static_cast<int32_t>(v * 8388607.0f);
                    tempBuf[i] = s24 << 8;
                }
                size_t written = stream.ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int32_t));
                size_t sw = written / sizeof(int32_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * sizeof(float);
        } else if (sf == 2) {
            int16_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    float v = std::clamp(fsrc[samplesProcessed + i], -1.0f, 1.0f);
                    tempBuf[i] = static_cast<int16_t>(v * 32767.0f);
                }
                size_t written = stream.ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int16_t));
                size_t sw = written / sizeof(int16_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * sizeof(float);
        } else {
            uint8_t tempBuf[3072];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    float v = std::clamp(fsrc[samplesProcessed + i], -1.0f, 1.0f);
                    int32_t val = static_cast<int32_t>(v * 8388607.0f);
                    tempBuf[3 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                    tempBuf[3 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    tempBuf[3 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                }
                size_t written = stream.ringBuffer.write(tempBuf, chunk * 3);
                size_t sw = written / 3;
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * sizeof(float);
        }
    } else if (pcmEncoding == 22 || pcmEncoding == 0x30000000) {
        const int32_t* in32 = reinterpret_cast<const int32_t*>(src);
        size_t numSamples = length / sizeof(int32_t);
        size_t samplesProcessed = 0;

        if (sf == 4) {
            return stream.ringBuffer.write(src, length);
        } else if (sf == 2) {
            int16_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    tempBuf[i] = static_cast<int16_t>(in32[samplesProcessed + i] >> 16);
                }
                size_t written = stream.ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int16_t));
                size_t sw = written / sizeof(int16_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * sizeof(int32_t);
        } else {
            uint8_t tempBuf[3072];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    int32_t val24 = in32[samplesProcessed + i] >> 8;
                    tempBuf[3 * i + 0] = static_cast<uint8_t>(val24 & 0xFF);
                    tempBuf[3 * i + 1] = static_cast<uint8_t>((val24 >> 8) & 0xFF);
                    tempBuf[3 * i + 2] = static_cast<uint8_t>((val24 >> 16) & 0xFF);
                }
                size_t written = stream.ringBuffer.write(tempBuf, chunk * 3);
                size_t sw = written / 3;
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * sizeof(int32_t);
        }
    } else {
        return stream.ringBuffer.write(src, length);
    }
}

// ---------------------------------------------------------------------------
// Thread-Safe Worker Lifecycle & JNI Implementation
// ---------------------------------------------------------------------------

static void stop_worker_thread_locked() {
    g_isRunning.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> cancelLock(g_urbCancelMutex);
        if (g_activeUrbFd >= 0 && !g_activeUrbs.empty()) {
            for (struct usbdevfs_urb* u : g_activeUrbs) {
                if (u != nullptr) {
                    ioctl(g_activeUrbFd, USBDEVFS_DISCARDURB, u);
                }
            }
        }
    }
    if (g_workerThread.joinable()) {
        g_workerThread.join();
    }
}

static void impl_suspendNativeUrbThread(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    stop_worker_thread_locked();
}

static jboolean impl_initNativeDac(JNIEnv*, jobject, jint fd, jint epOut, jint maxPacketSize, jint epInterval) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    stop_worker_thread_locked();

    int prevFd = g_nativeFd.exchange(fd);
    g_epOut.store(epOut);
    g_maxPacketSize.store(maxPacketSize);
    g_epInterval.store(epInterval > 0 ? epInterval : 1);

    if (prevFd != fd) {
        for (int i = 0; i < NUM_STREAMS; ++i) {
            g_streams[i].resetAll();
        }
    } else {
        for (int i = 0; i < NUM_STREAMS; ++i) {
            g_streams[i].resetDsp();
        }
    }

    if (fd >= 0) {
        g_isRunning.store(true, std::memory_order_release);
        g_workerThread = std::thread(audio_transfer_loop);
        LOGI("Native DAC initialized: FD=%d, EP=0x%02X, maxPkt=%d, interval=%d",
             fd, epOut, maxPacketSize, epInterval);
    }
    return JNI_TRUE;
}

static jboolean impl_setNativeFormat(JNIEnv*, jobject, jint srcSampleRate, jint targetSampleRate, jint bitDepthOrSubframeSize, jint channels) {
    int sf = bitDepthOrSubframeSize;
    if (sf > 4) {
        sf = (sf <= 16) ? 2 : 4;
    }
    if (sf < 2) sf = 2;

    int srcRate = (srcSampleRate > 0) ? srcSampleRate : 44100;
    int targetRate = (targetSampleRate > 0) ? targetSampleRate : srcRate;

    int prevTargetRate = g_sampleRate.exchange(targetRate);
    int prevSf = g_subframeSize.exchange(sf);
    g_channels.store(2);

    if (prevTargetRate != targetRate || prevSf != sf) {
        for (int i = 0; i < NUM_STREAMS; ++i) {
            g_streams[i].resetDsp();
        }
    }

    LOGI("Native DAC format: src=%d Hz (%dch) -> target=%d Hz (2ch), subframe=%d bytes (%d-bit container)",
         srcRate, channels > 0 ? channels : 2, targetRate, sf, sf * 8);
    return JNI_TRUE;
}

static void impl_setNativeStreamFormat(JNIEnv*, jobject, jint streamId, jint srcSampleRate, jint srcChannels) {
    int idx = (streamId == 1) ? 1 : 0;
    int rate = srcSampleRate > 0 ? srcSampleRate : 44100;
    int ch = srcChannels > 0 ? srcChannels : 2;
    int prevRate = g_streams[idx].sourceSampleRate.exchange(rate);
    int prevCh = g_streams[idx].sourceChannels.exchange(ch);
    if (prevRate != rate || prevCh != ch) {
        g_streams[idx].resetDsp();
    }
}

static jboolean impl_stopNativeDac(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    stop_worker_thread_locked();
    for (int i = 0; i < NUM_STREAMS; ++i) {
        g_streams[i].resetAll();
    }
    g_nativeFd.store(-1);
    g_epOut.store(0);
    LOGI("Native DAC stopped");
    return JNI_TRUE;
}

static void impl_clearNativeRingBuffer(JNIEnv*, jobject, jint streamId) {
    if (streamId < 0) {
        for (int i = 0; i < NUM_STREAMS; ++i) {
            g_streams[i].resetBufferAndDsp();
        }
    } else {
        int idx = (streamId == 1) ? 1 : 0;
        g_streams[idx].resetBufferAndDsp();
    }
}

static jlong impl_getNativePlayedFrames(JNIEnv*, jobject, jint streamId) {
    int idx = (streamId == 1) ? 1 : 0;
    return static_cast<jlong>(g_streams[idx].playedFrames.load(std::memory_order_relaxed));
}

static jint impl_getNativeRingBufferAvailable(JNIEnv*, jobject, jint streamId) {
    int idx = (streamId == 1) ? 1 : 0;
    return static_cast<jint>(g_streams[idx].ringBuffer.available());
}

static void impl_setNativePaused(JNIEnv*, jobject, jint streamId, jboolean paused) {
    if (streamId < 0) {
        for (int i = 0; i < NUM_STREAMS; ++i) {
            g_streams[i].isPaused.store(paused, std::memory_order_release);
        }
    } else {
        int idx = (streamId == 1) ? 1 : 0;
        g_streams[idx].isPaused.store(paused, std::memory_order_release);
    }
}

static void impl_setNativeVolumeScale(JNIEnv*, jobject, jfloat scale) {
    float clamped = std::clamp(static_cast<float>(scale), 0.0f, 1.0f);
    g_masterVolumeScale.store(clamped, std::memory_order_relaxed);
}

static void impl_setNativeStreamVolume(JNIEnv*, jobject, jint streamId, jfloat scale) {
    int idx = (streamId == 1) ? 1 : 0;
    float clamped = std::clamp(static_cast<float>(scale), 0.0f, 1.0f);
    g_streams[idx].volumeScale.store(clamped, std::memory_order_relaxed);
}

static jint impl_writeDirectBuffer(
    JNIEnv* env, jobject, jint streamId, jobject byteBuf, jint pos, jint len, jint pcmEncoding, jint targetBitDepth
) {
    if (!byteBuf || len <= 0) return 0;
    uint8_t* base = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuf));
    if (!base) return 0;
    return static_cast<jint>(process_and_write_pcm(streamId, base + pos, static_cast<size_t>(len), pcmEncoding, targetBitDepth));
}

static jint impl_writeByteArrayFormat(
    JNIEnv* env, jobject, jint streamId, jbyteArray pcmData, jint offset, jint length, jint pcmEncoding, jint targetBitDepth
) {
    if (!pcmData || length <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(pcmData, nullptr);
    if (!bytes) return 0;
    size_t processed = process_and_write_pcm(
        streamId,
        reinterpret_cast<const uint8_t*>(bytes + offset),
        static_cast<size_t>(length),
        pcmEncoding,
        targetBitDepth
    );
    env->ReleaseByteArrayElements(pcmData, bytes, JNI_ABORT);
    return static_cast<jint>(processed);
}

// ---------------------------------------------------------------------------
// JNI exports — dual package names
// ---------------------------------------------------------------------------

#define EXPORT_PAIR(pkg) \
extern "C" JNIEXPORT void JNICALL \
Java_##pkg##_UsbDirectDacManager_suspendNativeUrbThread(JNIEnv* e, jobject t) \
{ impl_suspendNativeUrbThread(e, t); } \
\
extern "C" JNIEXPORT jboolean JNICALL \
Java_##pkg##_UsbDirectDacManager_initNativeDac(JNIEnv* e, jobject t, jint fd, jint ep, jint pkt, jint interval) \
{ return impl_initNativeDac(e, t, fd, ep, pkt, interval); } \
\
extern "C" JNIEXPORT jboolean JNICALL \
Java_##pkg##_UsbDirectDacManager_setNativeFormat(JNIEnv* e, jobject t, jint srcRate, jint targetRate, jint bd, jint ch) \
{ return impl_setNativeFormat(e, t, srcRate, targetRate, bd, ch); } \
\
extern "C" JNIEXPORT void JNICALL \
Java_##pkg##_UsbDirectDacManager_nativeSetStreamFormat(JNIEnv* e, jobject t, jint streamId, jint srcRate, jint srcCh) \
{ impl_setNativeStreamFormat(e, t, streamId, srcRate, srcCh); } \
\
extern "C" JNIEXPORT jboolean JNICALL \
Java_##pkg##_UsbDirectDacManager_stopNativeDac(JNIEnv* e, jobject t) \
{ return impl_stopNativeDac(e, t); } \
\
extern "C" JNIEXPORT void JNICALL \
Java_##pkg##_UsbDirectDacManager_clearNativeRingBuffer(JNIEnv* e, jobject t, jint streamId) \
{ impl_clearNativeRingBuffer(e, t, streamId); } \
\
extern "C" JNIEXPORT jlong JNICALL \
Java_##pkg##_UsbDirectDacManager_nativeGetPlayedFrames(JNIEnv* e, jobject t, jint streamId) \
{ return impl_getNativePlayedFrames(e, t, streamId); } \
\
extern "C" JNIEXPORT jint JNICALL \
Java_##pkg##_UsbDirectDacManager_nativeGetRingBufferAvailable(JNIEnv* e, jobject t, jint streamId) \
{ return impl_getNativeRingBufferAvailable(e, t, streamId); } \
\
extern "C" JNIEXPORT void JNICALL \
Java_##pkg##_UsbDirectDacManager_nativeSetPaused(JNIEnv* e, jobject t, jint streamId, jboolean p) \
{ impl_setNativePaused(e, t, streamId, p); } \
\
extern "C" JNIEXPORT void JNICALL \
Java_##pkg##_UsbDirectDacManager_nativeSetVolumeScale(JNIEnv* e, jobject t, jfloat scale) \
{ impl_setNativeVolumeScale(e, t, scale); } \
\
extern "C" JNIEXPORT void JNICALL \
Java_##pkg##_UsbDirectDacManager_nativeSetStreamVolume(JNIEnv* e, jobject t, jint streamId, jfloat scale) \
{ impl_setNativeStreamVolume(e, t, streamId, scale); } \
\
extern "C" JNIEXPORT jint JNICALL \
Java_##pkg##_UsbDirectDacManager_nativeWriteDirectBuffer(JNIEnv* e, jobject t, jint streamId, jobject b, jint pos, jint len, jint enc, jint targetBd) \
{ return impl_writeDirectBuffer(e, t, streamId, b, pos, len, enc, targetBd); } \
\
extern "C" JNIEXPORT jint JNICALL \
Java_##pkg##_UsbDirectDacManager_nativeWriteByteArrayFormat(JNIEnv* e, jobject t, jint streamId, jbyteArray d, jint off, jint len, jint enc, jint targetBd) \
{ return impl_writeByteArrayFormat(e, t, streamId, d, off, len, enc, targetBd); }

EXPORT_PAIR(com_yoopen_player_data_usb)
EXPORT_PAIR(open_yo_player_data_usb)
EXPORT_PAIR(com_hitarashi_multibox_data_usb)
