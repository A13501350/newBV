package dev.frost819.newbv.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.frost819.newbv.player.entity.AudioBalanceLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 音频处理器适配器：把 [VolumeNormalizer]（纯 JVM DSP）挂进
 * [androidx.media3.exoplayer.audio.DefaultAudioSink] 的处理器链。
 *
 * 常驻处理器链，[AudioBalanceLevel.OFF] 时走字节级直通——因此档位切换
 * 无需重建播放器/音频管线，下一个音频块即生效。
 *
 * 仅支持 PCM 16-bit 与 PCM float；其它编码抛
 * [AudioProcessor.UnhandledAudioFormatException]（与 [androidx.media3.common.audio.SonicAudioProcessor]
 * 惯例一致，由 AudioSink 统一降级）。B 站音频解码后均为 PCM，该分支实际不可达。
 */
@OptIn(UnstableApi::class)
class VolumeBalanceAudioProcessor : BaseAudioProcessor() {
    private val normalizer = VolumeNormalizer(AudioBalanceLevel.OFF)

    /**
     * 自行跟踪的已配置格式。
     *
     * 不读基类 [inputAudioFormat]：该字段在 flush() 时才从 pending 提升，
     * 而 isActive 在 configure() 后即为 true，两者时序不一致。
     */
    private var activeFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    /** 当前档位。volatile 写入，任意线程调用；变更在下一个音频块生效。 */
    var level: AudioBalanceLevel
        get() = normalizer.level
        set(value) {
            normalizer.level = value
        }

    /**
     * 当前处理状态快照（增益 / 节目响度 / 校准进度）。
     *
     * 供调试信息观测与测试断言；与处理线程并发时读到的可能是中间态。
     */
    fun snapshotStats(): VolumeNormalizer.Stats = normalizer.snapshot()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                activeFormat = inputAudioFormat
                normalizer.configure(
                    sampleRateHz = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE_HZ,
                    channelCount = inputAudioFormat.channelCount.takeIf { it > 0 } ?: DEFAULT_CHANNEL_COUNT,
                )
                inputAudioFormat
            }

            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        out.order(ByteOrder.nativeOrder())

        // OFF 档：字节级直通，零处理开销
        if (level == AudioBalanceLevel.OFF || !isActive) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        when (activeFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                val samples = FloatArray(size / FLOAT_BYTES_PER_SAMPLE)
                inputBuffer
                    .duplicate()
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .get(samples)
                normalizer.processPcmFloat(samples, samples.size)
                out.asFloatBuffer().put(samples)
                out.position(out.position() + samples.size * FLOAT_BYTES_PER_SAMPLE)
            }

            else -> {
                val samples = ShortArray(size / SHORT_BYTES_PER_SAMPLE)
                inputBuffer
                    .duplicate()
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer()
                    .get(samples)
                normalizer.processPcm16(samples, samples.size)
                out.asShortBuffer().put(samples)
                out.position(out.position() + samples.size * SHORT_BYTES_PER_SAMPLE)
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        // flush 表示流切换/seek 到新流，节目响度需重新校准
        normalizer.reset()
    }

    override fun onReset() {
        normalizer.reset()
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_CHANNEL_COUNT = 2
        const val SHORT_BYTES_PER_SAMPLE = 2
        const val FLOAT_BYTES_PER_SAMPLE = 4
    }
}
