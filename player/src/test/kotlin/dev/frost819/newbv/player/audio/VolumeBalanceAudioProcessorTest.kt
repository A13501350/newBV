package dev.frost819.newbv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.frost819.newbv.player.entity.AudioBalanceLevel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [VolumeBalanceAudioProcessor] 的集成测试。
 *
 * 在 JVM 上走完整的 media3 AudioProcessor 协议（configure → queueInput →
 * getOutput → flush），验证格式协商、直通、端到端增益、档位热切换与
 * 不支持编码的异常路径。DSP 数值行为由 [VolumeNormalizerTest] 覆盖。
 */
class VolumeBalanceAudioProcessorTest {
    // ── 格式协商 ──────────────────────────────────────────

    @Test
    fun `configure accepts PCM 16-bit and returns same format`() {
        // Given：PCM 16-bit 48kHz 双声道格式
        val processor = VolumeBalanceAudioProcessor()
        val format = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)

        // When：配置处理器
        val result = processor.configure(format)

        // Then：原样接受
        assertEquals(format, result)
        assertTrue(processor.isActive)
    }

    @Test
    fun `configure accepts PCM float and returns same format`() {
        val processor = VolumeBalanceAudioProcessor()
        val format = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_FLOAT)

        val result = processor.configure(format)

        assertEquals(format, result)
        assertTrue(processor.isActive)
    }

    @Test
    fun `configure rejects non-PCM encoding`() {
        // Given：非 PCM 编码（如 AC-3，解码后才归 AudioSink 处理器链管）
        val processor = VolumeBalanceAudioProcessor()
        val format = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_AC3)

        // When / Then：抛 UnhandledAudioFormatException，由 AudioSink 降级
        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            processor.configure(format)
        }
    }

    // ── 直通 ──────────────────────────────────────────────

    @Test
    fun `OFF level passes bytes through unchanged`() {
        // Given：OFF 档（默认）+ 已配置 16-bit 格式 + 伪随机 PCM
        val processor = VolumeBalanceAudioProcessor()
        processor.configure(pcm16Format())
        val input = randomPcm16Bytes(blockCount = 4)

        // When：整块送入并读出
        val output = queueAndDrain(processor, input)

        // Then：字节逐位一致
        assertArrayEquals(input, output)
        assertEquals(0f, processor.snapshotStats().gainDb, 1e-6f)
    }

    // ── 端到端增益 ────────────────────────────────────────

    @Test
    fun `quiet input is amplified end to end`() {
        // Given：MEDIUM 档，RMS -30dBFS 的安静正弦（期望增益收敛到档位上限 +2dB；
        // 目标响度 -20dBFS 下，-15dBFS 已属偏响内容，会被衰减）
        val processor = VolumeBalanceAudioProcessor()
        processor.level = AudioBalanceLevel.MEDIUM
        processor.configure(pcm16Format())
        val inputRmsDb = -30.0
        val amplitude = amplitudeForRms(inputRmsDb)

        // When：喂 30 秒（1024 帧/块，贴近真实音频块大小）
        var lastOutput = ShortArray(0)
        repeat(30 * SAMPLE_RATE / BLOCK_FRAMES) {
            val block = sine16(BLOCK_FRAMES, amplitude)
            val bytes = shortsToBytes(block)
            val out = queueAndDrain(processor, bytes)
            lastOutput = bytesToShorts(out)
        }

        // Then：输出响度 = 输入 + 约 2dB（高于输入）
        val outputRmsDb = rmsDbOf(lastOutput)
        assertTrue(outputRmsDb > inputRmsDb + 1.5) { "expected amplification, got $outputRmsDb vs $inputRmsDb" }
        assertTrue(processor.snapshotStats().calibrated)
    }

    // ── 档位热切换 ────────────────────────────────────────

    @Test
    fun `switching to OFF passes through on next block`() {
        // Given：MEDIUM 档处理中的处理器
        val processor = VolumeBalanceAudioProcessor()
        processor.level = AudioBalanceLevel.MEDIUM
        processor.configure(pcm16Format())
        val block = sine16(BLOCK_FRAMES, amplitudeForRms(-15.0))
        val bytes = shortsToBytes(block)
        queueAndDrain(processor, bytes)

        // When：切到 OFF 并送下一块
        processor.level = AudioBalanceLevel.OFF
        val input = shortsToBytes(sine16(BLOCK_FRAMES, amplitudeForRms(-15.0)))
        val output = queueAndDrain(processor, input)

        // Then：字节级直通
        assertArrayEquals(input, output)
    }

    // ── flush 语义 ────────────────────────────────────────

    @Test
    fun `flush resets calibration`() {
        // Given：已校准的处理器（校准窗 2s ≈ 94 块 @1024 帧/块，喂 150 块确保完成）
        val processor = VolumeBalanceAudioProcessor()
        processor.level = AudioBalanceLevel.MEDIUM
        processor.configure(pcm16Format())
        repeat(150) {
            queueAndDrain(processor, shortsToBytes(sine16(BLOCK_FRAMES, amplitudeForRms(-15.0))))
        }
        assertTrue(processor.snapshotStats().calibrated)

        // When：flush（流切换）
        processor.flush()

        // Then：校准状态清空
        assertFalse(processor.snapshotStats().calibrated)
    }

    // ── 边界 ──────────────────────────────────────────────

    @Test
    fun `empty input produces no output and does not crash`() {
        val processor = VolumeBalanceAudioProcessor()
        processor.configure(pcm16Format())

        processor.queueInput(ByteBuffer.allocate(0))

        assertFalse(processor.getOutput().hasRemaining())
    }

    // ── 测试辅助 ──────────────────────────────────────────

    private fun pcm16Format() = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)

    /** 送入一块并读出全部输出字节。 */
    private fun queueAndDrain(
        processor: VolumeBalanceAudioProcessor,
        input: ByteArray,
    ): ByteArray {
        processor.queueInput(ByteBuffer.wrap(input))
        val out = processor.getOutput()
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        return bytes
    }

    /** 固定种子的伪随机 16-bit PCM（偶数字节，保证帧对齐）。 */
    private fun randomPcm16Bytes(blockCount: Int): ByteArray {
        val rng = java.util.Random(42)
        val shorts = ShortArray(blockCount * BLOCK_FRAMES * CHANNELS) { rng.nextInt(-32_768, 32_767).toShort() }
        return shortsToBytes(shorts)
    }

    /** 生成 48kHz 双声道正弦（16-bit，归一化到 /32768）。 */
    private fun sine16(
        frames: Int,
        amplitude: Double,
        frequency: Double = 440.0,
    ): ShortArray {
        val samples = ShortArray(frames * CHANNELS)
        for (frame in 0 until frames) {
            val v = amplitude * sin(2.0 * PI * frequency * frame / SAMPLE_RATE)
            samples[frame * 2] = (v * 32_767.0).roundToInt().toShort()
            samples[frame * 2 + 1] = (v * 32_767.0).roundToInt().toShort()
        }
        return samples
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(shorts)
        return buffer.array()
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val shorts = ShortArray(bytes.size / 2)
        buffer.asShortBuffer().get(shorts)
        return shorts
    }

    /** 由目标 RMS（dBFS）反推正弦峰值幅度。 */
    private fun amplitudeForRms(rmsDb: Double): Double = 10.0.pow(rmsDb / 20.0) * sqrt(2.0)

    private fun rmsDbOf(samples: ShortArray): Double {
        if (samples.isEmpty()) return -120.0
        var sumSquares = 0.0
        for (s in samples) {
            val v = s.toDouble() / 32_768.0
            sumSquares += v * v
        }
        val rms = sqrt(sumSquares / samples.size)
        return if (rms > 0.0) 20.0 * log10(rms) else -120.0
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BLOCK_FRAMES = 1_024
    }
}
