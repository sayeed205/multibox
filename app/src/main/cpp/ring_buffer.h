#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <algorithm>

class LockFreeRingBuffer {
private:
    uint8_t* buffer_;
    size_t capacity_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};

public:
    explicit LockFreeRingBuffer(size_t capacity) : capacity_(capacity) {
        buffer_ = new uint8_t[capacity];
        memset(buffer_, 0, capacity);
    }

    ~LockFreeRingBuffer() {
        delete[] buffer_;
    }

    size_t write(const uint8_t* data, size_t bytes) {
        size_t head = head_.load(std::memory_order_relaxed);
        size_t tail = tail_.load(std::memory_order_acquire);
        size_t used = (head >= tail) ? (head - tail) : 0;
        size_t available_space = (used < capacity_) ? (capacity_ - used) : 0;

        if (bytes > available_space) {
            bytes = available_space;
        }
        if (bytes == 0) return 0;

        size_t offset = head % capacity_;
        size_t first_chunk = std::min(bytes, capacity_ - offset);

        memcpy(buffer_ + offset, data, first_chunk);
        if (bytes > first_chunk) {
            memcpy(buffer_, data + first_chunk, bytes - first_chunk);
        }

        head_.store(head + bytes, std::memory_order_release);
        return bytes;
    }

    size_t read(uint8_t* destination, size_t bytes) {
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t head = head_.load(std::memory_order_acquire);
        size_t available_data = (head >= tail) ? (head - tail) : 0;

        size_t to_read = std::min(bytes, available_data);
        size_t underrun_bytes = bytes - to_read;

        if (to_read > 0) {
            size_t offset = tail % capacity_;
            size_t first_chunk = std::min(to_read, capacity_ - offset);

            memcpy(destination, buffer_ + offset, first_chunk);
            if (to_read > first_chunk) {
                memcpy(destination + first_chunk, buffer_, to_read - first_chunk);
            }
            tail_.store(tail + to_read, std::memory_order_release);
        }

        // Fill underrun with silence (zeros) to eliminate pops and clicks
        if (underrun_bytes > 0) {
            memset(destination + to_read, 0, underrun_bytes);
        }

        return to_read;
    }

    size_t available() const {
        size_t tail = tail_.load(std::memory_order_acquire);
        size_t head = head_.load(std::memory_order_acquire);
        return (head >= tail) ? (head - tail) : 0;
    }

    size_t capacity() const {
        return capacity_;
    }

    void reset() {
        size_t head = head_.load(std::memory_order_acquire);
        tail_.store(head, std::memory_order_release);
    }
};

#endif // RING_BUFFER_H
