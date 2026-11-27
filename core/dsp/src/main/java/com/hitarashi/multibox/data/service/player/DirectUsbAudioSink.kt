package com.hitarashi.multibox.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.hitarashi.multibox.data.usb.UsbDirectDacManager
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class DirectUsbAudioSink(
    delegate: AudioSink,
    private val usbDacManager: UsbDirectDacManager
) : ForwardingAudioSink(delegate) {

    private var currentSampleRate = 44100
    private var currentChannels = 2
    private var currentBitDepth = 16
    private var currentEncoding = C.ENCODING_PCM_16BIT
    private var lastConfiguredRate = -1
    private var lastConfiguredBitDepth = -1
    private var lastConfiguredChannels = -1

    private var isSinkPlaying = false
    private var sinkVolume = 1.0f

    private var anchorPresentationTimeUs: Long = C.TIME_UNSET
    private var anchorFrameIndex: Long = 0L
    private var lastReportedPositionUs: Long = C.TIME_UNSET
    private var needsClockReanchor = true
    private var handledEndOfStream = false

    private val streamId: Int
        get() = usbDacManager.acquireStreamSlot(this)

    override fun supportsFormat(format: Format): Boolean {
        if (usbDacManager.isExclusiveActive && MimeTypes.AUDIO_RAW == format.sampleMimeType) {
            val enc = format.pcmEncoding
            if (enc == C.ENCODING_PCM_16BIT ||
                enc == C.ENCODING_PCM_24BIT ||
                enc == C.ENCODING_PCM_32BIT ||
                enc == C.ENCODING_PCM_FLOAT
            ) {
                return true
            }
        }
        return super.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): @AudioSink.SinkFormatSupport Int {
        if (usbDacManager.isExclusiveActive && MimeTypes.AUDIO_RAW == format.sampleMimeType) {
            val enc = format.pcmEncoding
            if (enc == C.ENCODING_PCM_16BIT ||
                enc == C.ENCODING_PCM_24BIT ||
                enc == C.ENCODING_PCM_32BIT ||
                enc == C.ENCODING_PCM_FLOAT
            ) {
                return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            }
        }
        return super.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (usbDacManager.isExclusiveActive) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return super.getFormatOffloadSupport(format)
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val inputFormat = audioSinkConfig.format
        currentSampleRate = if (inputFormat.sampleRate != Format.NO_VALUE && inputFormat.sampleRate > 0) inputFormat.sampleRate else 44100
        currentChannels = if (inputFormat.channelCount != Format.NO_VALUE && inputFormat.channelCount > 0) inputFormat.channelCount else 2
        currentEncoding = if (inputFormat.pcmEncoding != Format.NO_VALUE && inputFormat.pcmEncoding != C.ENCODING_INVALID) {
            inputFormat.pcmEncoding
        } else {
            C.ENCODING_PCM_16BIT
        }
        currentBitDepth = when (currentEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

        handledEndOfStream = false
        needsClockReanchor = true

        if (usbDacManager.isExclusiveActive) {
            val sid = streamId
            usbDacManager.setNativeStreamVolume(sid, sinkVolume)
            usbDacManager.setNativeStreamPaused(sid, !isSinkPlaying)

            if (isSinkPlaying || !usbDacManager.isOtherStreamActive(sid)) {
                lastConfiguredRate = currentSampleRate
                lastConfiguredBitDepth = currentBitDepth
                lastConfiguredChannels = currentChannels
                usbDacManager.configureSampleRateAndFormat(
                    sampleRate = currentSampleRate,
                    bitDepth = currentBitDepth,
                    channels = currentChannels,
                    streamId = sid
                )
            } else {
                // Auxiliary player pre-buffering while the master stream is playing:
                // only set software stream format so the master's hardware clock is untouched.
                usbDacManager.setStreamFormatOnly(sid, currentSampleRate, currentChannels)
            }
            runCatching { super.configure(audioSinkConfig) }
        } else {
            super.configure(audioSinkConfig)
        }
    }

    override fun handleDiscontinuity() {
        handledEndOfStream = false
        needsClockReanchor = true
        lastReportedPositionUs = C.TIME_UNSET
        super.handleDiscontinuity()
    }

    override fun setVolume(volume: Float) {
        sinkVolume = volume.coerceIn(0f, 1f)
        if (usbDacManager.isExclusiveActive) {
            usbDacManager.setNativeStreamVolume(streamId, sinkVolume)
        }
        super.setVolume(volume)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!usbDacManager.isExclusiveActive) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        if (!buffer.hasRemaining()) {
            return true
        }

        handledEndOfStream = false
        val sid = streamId

        usbDacManager.setNativeStreamPaused(sid, !isSinkPlaying)

        if (lastConfiguredRate != currentSampleRate ||
            lastConfiguredBitDepth != currentBitDepth ||
            lastConfiguredChannels != currentChannels
        ) {
            if (isSinkPlaying || !usbDacManager.isOtherStreamActive(sid)) {
                lastConfiguredRate = currentSampleRate
                lastConfiguredBitDepth = currentBitDepth
                lastConfiguredChannels = currentChannels
                usbDacManager.configureSampleRateAndFormat(
                    sampleRate = currentSampleRate,
                    bitDepth = currentBitDepth,
                    channels = currentChannels,
                    streamId = sid
                )
                usbDacManager.setNativeStreamVolume(sid, sinkVolume)
                usbDacManager.setNativeStreamPaused(sid, !isSinkPlaying)
                needsClockReanchor = true
            } else {
                usbDacManager.setStreamFormatOnly(sid, currentSampleRate, currentChannels)
            }
        }

        val targetRate = usbDacManager.activeTargetSampleRate
        val sf = usbDacManager.activeSubframeSize.coerceIn(2, 4)
        val sr = if (targetRate > 0) targetRate else (if (currentSampleRate > 0) currentSampleRate else 44100)
        val outBytesPerFrame = 2 * sf
        val maxBufferedBytes = (sr * outBytesPerFrame * 140) / 1000 // ~140 ms runway

        val currentAvailable = usbDacManager.getNativeRingBufferAvailable(sid)
        if (currentAvailable >= maxBufferedBytes) {
            return false
        }

        val srcBps = when (currentEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val srcBytesPerFrame = (if (currentChannels > 0) currentChannels else 2) * srcBps

        if (needsClockReanchor || anchorPresentationTimeUs == C.TIME_UNSET) {
            val playedOutFrames = usbDacManager.getNativePlayedFrames(sid)
            val queuedOutFrames = (currentAvailable / outBytesPerFrame).toLong()
            anchorPresentationTimeUs = presentationTimeUs
            anchorFrameIndex = playedOutFrames + queuedOutFrames
            needsClockReanchor = false
        }

        val posBefore = buffer.position()
        val consumedBytes = usbDacManager.writeBuffer(
            streamId = sid,
            buffer = buffer,
            pcmEncoding = currentEncoding,
            targetBitDepth = sf
        )

        if (buffer.hasRemaining()) {
            if (buffer.remaining() < srcBytesPerFrame) {
                buffer.position(buffer.limit())
            } else if (consumedBytes > 0 && buffer.position() > posBefore) {
                val playedOutFrames = usbDacManager.getNativePlayedFrames(sid)
                val queuedOutFrames = (usbDacManager.getNativeRingBufferAvailable(sid) / outBytesPerFrame).toLong()
                val consumedSrcFrames = (consumedBytes / srcBytesPerFrame).toLong()
                val consumedDurationUs = (consumedSrcFrames * 1_000_000L) / currentSampleRate.coerceAtLeast(1)
                anchorPresentationTimeUs = presentationTimeUs + consumedDurationUs
                anchorFrameIndex = playedOutFrames + queuedOutFrames
            }
        } else {
            needsClockReanchor = true
        }

        return !buffer.hasRemaining()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!usbDacManager.isExclusiveActive) {
            return super.getCurrentPositionUs(sourceEnded)
        }

        if (anchorPresentationTimeUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }

        val sid = streamId
        val playedFrames = usbDacManager.getNativePlayedFrames(sid)
        val targetRate = usbDacManager.activeTargetSampleRate
        val rate = if (targetRate > 0) targetRate else (if (currentSampleRate > 0) currentSampleRate else 44100)
        val deltaFrames = playedFrames - anchorFrameIndex
        val rawPositionUs = (anchorPresentationTimeUs + (deltaFrames * 1_000_000L) / rate).coerceAtLeast(0L)

        val monotonicPos = if (lastReportedPositionUs != C.TIME_UNSET) {
            maxOf(lastReportedPositionUs, rawPositionUs)
        } else {
            rawPositionUs
        }
        lastReportedPositionUs = monotonicPos
        return monotonicPos
    }

    override fun isEnded(): Boolean {
        if (!usbDacManager.isExclusiveActive) {
            return super.isEnded()
        }
        return handledEndOfStream && !hasPendingData()
    }

    override fun hasPendingData(): Boolean {
        if (!usbDacManager.isExclusiveActive) {
            return super.hasPendingData()
        }
        return usbDacManager.getNativeRingBufferAvailable(streamId) > 0
    }

    override fun playToEndOfStream() {
        if (!usbDacManager.isExclusiveActive) {
            super.playToEndOfStream()
            return
        }
        handledEndOfStream = true
    }

    override fun play() {
        isSinkPlaying = true
        if (usbDacManager.isExclusiveActive) {
            val sid = streamId
            if (lastConfiguredRate != currentSampleRate ||
                lastConfiguredBitDepth != currentBitDepth ||
                lastConfiguredChannels != currentChannels
            ) {
                lastConfiguredRate = currentSampleRate
                lastConfiguredBitDepth = currentBitDepth
                lastConfiguredChannels = currentChannels
                usbDacManager.configureSampleRateAndFormat(
                    sampleRate = currentSampleRate,
                    bitDepth = currentBitDepth,
                    channels = currentChannels,
                    streamId = sid
                )
            }
            usbDacManager.setNativeStreamVolume(sid, sinkVolume)
            usbDacManager.setNativeStreamPaused(sid, false)
        } else {
            super.play()
        }
    }

    override fun pause() {
        isSinkPlaying = false
        if (usbDacManager.isExclusiveActive) {
            usbDacManager.setNativeStreamPaused(streamId, true)
        } else {
            super.pause()
        }
    }

    override fun flush() {
        anchorPresentationTimeUs = C.TIME_UNSET
        anchorFrameIndex = 0L
        lastReportedPositionUs = C.TIME_UNSET
        needsClockReanchor = true
        handledEndOfStream = false
        val sid = streamId
        usbDacManager.safeClearNativeRingBuffer(sid)
        usbDacManager.setNativeStreamPaused(sid, !isSinkPlaying)
        super.flush()
    }

    override fun reset() {
        isSinkPlaying = false
        anchorPresentationTimeUs = C.TIME_UNSET
        anchorFrameIndex = 0L
        lastReportedPositionUs = C.TIME_UNSET
        needsClockReanchor = true
        handledEndOfStream = false
        lastConfiguredRate = -1
        lastConfiguredBitDepth = -1
        lastConfiguredChannels = -1
        usbDacManager.releaseStreamSlot(this)
        super.reset()
    }
}
