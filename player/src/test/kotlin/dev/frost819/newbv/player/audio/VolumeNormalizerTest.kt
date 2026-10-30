package dev.frost819.newbv.player.audio

import dev.frost819.newbv.player.entity.AudioBalanceLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [VolumeNormalizer] 的单元测试。
 *
 * 用合成正弦/成形信号驱动 DSP 状态机，验证：
 * 直通、校准收敛到目标响度、档位增益钳制、峰值保护、静音门限、
 * 档位切换重置、float/16-bit 路径一致性、分块不变性与 reset 语义。
 * 不依赖 Android / Media3，纯 JVM 运行。
 */
class VolumeNormalizerTest {
    // ── 直通 ──────────────────────────────────────────────

    @Test
    fun `OFF level leaves samples unchanged`() {
        // Given：OFF 档归一器
        val normalizer = VolumeNormalizer(AudioBalanceLevel.OFF)
        val input = sine16(frames = 4_800, amplitude = 0.3)
        val expected = input.copyOf()

        // When：处理一块音频
        normalizer.processPcm16(input, input.size)

        // Then：样本逐位不变（直通）
        assertTrue(input.contentEquals(expected))
        assertEquals(0f, normalizer.snapshot().gainDb, 1e-6f)
    }

    // ── 校准与收敛 ────────────────────────────────────────

    @Test
    fun `calibrates and applies level-clamped gain for quiet program`() {
        // Given：MEDIUM 档（起播 -1.5dB，最大 +2.0dB），RMS 约 -15dBFS 的节目
        val normalizer = VolumeNormalizer(AudioBalanceLevel.MEDIUM)
        val inputRmsDb = -15.0
        val amplitude = amplitudeForRms(inputRmsDb)

        // When：喂 30 秒（校准窗 2s + 充分收敛）
        val lastOutput = feed16(normalizer, seconds = 30, amplitude = amplitude)

        // Then：校准完成，增益收敛到档位上限 +2dB，输出响度 = 输入 + 增益
        val stats = normalizer.snapshot()
        assertTrue(stats.calibrated)
        assertEquals(inputRmsDb, stats.programRmsDb!!.toDouble(), 0.5)
        assertEquals(2.0f, stats.gainDb, 0.2f)
        assertEquals(inputRmsDb + 2.0f, rmsDbOf(lastOutput), 0.3)
    }

    @Test
    fun `converges loud program toward target loudness`() {
        // Given：HIGH 档（负向最小 -16dB），RMS 约 -5dBFS 的响节目，
        // 目标响度 -20dBFS 落在档位调节范围内
        val normalizer = VolumeNormalizer(AudioBalanceLevel.HIGH)
        val inputRmsDb = -5.0
        val amplitude = amplitudeForRms(inputRmsDb)

        // When：喂 30 秒（attack 1.5s，收敛快）
        val lastOutput = feed16(normalizer, seconds = 30, amplitude = amplitude)

        // Then：输出响度收敛到目标 -20dBFS 附近
        val stats = normalizer.snapshot()
        assertTrue(stats.calibrated)
        assertEquals(-20.0, rmsDbOf(lastOutput), 0.5)
    }

    // ── 峰值保护 ──────────────────────────────────────────

    @Test
    fun `limiter caps output peak at ceiling`() {
        // Given：MEDIUM 档已收敛到正增益，随后出现高峰值块（peak 0.85）。
        // 增益 +2dB（约 1.26x）会把峰值推到约 1.07，峰值保护应把增益压回 0.98/0.85
        val normalizer = VolumeNormalizer(AudioBalanceLevel.MEDIUM)
        feed16(normalizer, seconds = 30, amplitude = amplitudeForRms(-25.0))
        assertTrue(normalizer.snapshot().gainDb > 1.5f)

        // When：处理峰值 0.85 的脉冲状块
        val peaky = squareish16(frames = 4_800, highAmplitude = 0.85, dutyCycle = 0.2)
        normalizer.processPcm16(peaky, peaky.size)

        // Then：输出峰值不超过 LIMITER_CEILING
        val peak = peakOf(peaky)
        assertTrue(peak <= 0.981) { "output peak $peak exceeds limiter ceiling" }
    }

    // ── 静音门限 ──────────────────────────────────────────

    @Test
    fun `silence does not drive calibration`() {
        // Given：MEDIUM 档
        val normalizer = VolumeNormalizer(AudioBalanceLevel.MEDIUM)

        // When：喂 10 秒纯静音
        repeat(10) {
            val silence = ShortArray(48_000 * 2)
            normalizer.processPcm16(silence, silence.size)
        }

        // Then：未校准，增益保持起播值
        val stats = normalizer.snapshot()
        assertFalse(stats.calibrated)
        assertEquals(-1.5f, stats.gainDb, 1e-3f)

        // When：随后喂有效信号，校准应从头开始并完成
        feed16(normalizer, seconds = 5, amplitude = amplitudeForRms(-15.0))

        // Then：校准完成
        assertTrue(normalizer.snapshot().calibrated)
    }

    // ── 档位切换 ──────────────────────────────────────────

    @Test
    fun `level switch resets adaptive state on next block`() {
        // Given：MEDIUM 档已校准并稳定跟踪
        val normalizer = VolumeNormalizer(AudioBalanceLevel.MEDIUM)
        feed16(normalizer, seconds = 10, amplitude = amplitudeForRms(-15.0))
        assertTrue(normalizer.snapshot().calibrated)

        // When：切到 LOW 档并处理下一块
        normalizer.level = AudioBalanceLevel.LOW
        val block = sine16(frames = 4_800, amplitude = amplitudeForRms(-15.0))
        normalizer.processPcm16(block, block.size)

        // Then：自适应状态重置——校准清空，增益回到 LOW 起播值 -0.5dB
        val stats = normalizer.snapshot()
        assertEquals(AudioBalanceLevel.LOW, stats.level)
        assertFalse(stats.calibrated)
        assertEquals(-0.5f, stats.gainDb, 1e-3f)
    }

    // ── reset 语义 ────────────────────────────────────────

    @Test
    fun `reset clears calibration and restores startup gain`() {
        // Given：已收敛的 MEDIUM 档
        val normalizer = VolumeNormalizer(AudioBalanceLevel.MEDIUM)
        feed16(normalizer, seconds = 30, amplitude = amplitudeForRms(-15.0))
        assertTrue(normalizer.snapshot().calibrated)

        // When：reset
        normalizer.reset()

        // Then：校准清空，增益回到起播值
        val stats = normalizer.snapshot()
        assertFalse(stats.calibrated)
        assertEquals(-1.5f, stats.gainDb, 1e-3f)
    }

    // ── float / 16-bit 路径一致性 ─────────────────────────

    @Test
    fun `float path tracks 16-bit path gain trajectory`() {
        // Given：两个归一器，同参数、同信号（float 与 16-bit 等价表示）
        val amplitude = amplitudeForRms(-15.0)
        val normalizer16 = VolumeNormalizer(AudioBalanceLevel.MEDIUM)
        val normalizerFloat = VolumeNormalizer(AudioBalanceLevel.MEDIUM)

        // When：各喂 30 秒
        feed16(normalizer16, seconds = 30, amplitude = amplitude)
        feedFloat(normalizerFloat, seconds = 30, amplitude = amplitude)

        // Then：两条路径的增益轨迹一致（量化差异 < 0.05dB）
        assertEquals(normalizer16.snapshot().gainDb, normalizerFloat.snapshot().gainDb, 0.05f)
    }

    // ── 分块不变性 ────────────────────────────────────────

    @Test
    fun `gain trajectory is nearly invariant to block size`() {
        // Given：同一 30 秒信号，分别按 1 秒块与 10ms 块喂入
        val amplitude = amplitudeForRms(-15.0)
        val coarse = VolumeNormalizer(AudioBalanceLevel.MEDIUM)
        val fine = VolumeNormalizer(AudioBalanceLevel.MEDIUM)

        // When：coarse 喂 1s 块，fine 喂 10ms 块
        feed16(coarse, seconds = 30, amplitude = amplitude)
        feed16(fine, seconds = 30, amplitude = amplitude, framesPerBlock = 480)

        // Then：最终增益近似一致（离散化差异 < 0.3dB）
        assertEquals(coarse.snapshot().gainDb, fine.snapshot().gainDb, 0.3f)
    }

    // ── 边界 ──────────────────────────────────────────────

    @Test
    fun `empty block is a no-op`() {
        val normalizer = VolumeNormalizer(AudioBalanceLevel.MEDIUM)
        val samples = ShortArray(0)

        normalizer.processPcm16(samples, 0)
        normalizer.processPcmFloat(FloatArray(0), 0)

        assertEquals(-1.5f, normalizer.snapshot().gainDb, 1e-3f)
    }

    @Test
    fun `invalid format falls back to defaults without crash`() {
        // Given：非法采样率/声道数
        val normalizer = VolumeNormalizer(AudioBalanceLevel.MEDIUM)
        normalizer.configure(sampleRateHz = 0, channelCount = 0)

        // When：喂常规块
        val output = feed16(normalizer, seconds = 5, amplitude = amplitudeForRms(-15.0))

        // Then：回退默认格式正常处理（校准完成）
        assertTrue(normalizer.snapshot().calibrated)
        assertTrue(output.isNotEmpty())
    }

    // ── 测试辅助 ──────────────────────────────────────────

    /** 每秒一块喂 16-bit 正弦，返回最后一块处理后的样本。 */
    private fun feed16(
        normalizer: VolumeNormalizer,
        seconds: Int,
        amplitude: Double,
        framesPerBlock: Int = 48_000,
    ): ShortArray {
        var last = ShortArray(0)
        repeat(seconds) {
            val block = sine16(frames = framesPerBlock, amplitude = amplitude)
            normalizer.processPcm16(block, block.size)
            last = block
        }
        return last
    }

    /** 每秒一块喂 float 正弦，信号内容与 [feed16] 等价。 */
    private fun feedFloat(
        normalizer: VolumeNormalizer,
        seconds: Int,
        amplitude: Double,
    ): FloatArray {
        var last = FloatArray(0)
        repeat(seconds) {
            val block = FloatArray(48_000 * 2)
            for (frame in 0 until 48_000) {
                val v = amplitude * sin(2.0 * PI * 440.0 * frame / 48_000.0)
                block[frame * 2] = v.toFloat()
                block[frame * 2 + 1] = v.toFloat()
            }
            normalizer.processPcmFloat(block, block.size)
            last = block
        }
        return last
    }

    /** 生成 48kHz 双声道正弦（16-bit，归一化到 /32768）。 */
    private fun sine16(
        frames: Int,
        amplitude: Double,
        frequency: Double = 440.0,
    ): ShortArray {
        val samples = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val v = amplitude * sin(2.0 * PI * frequency * frame / 48_000.0)
            samples[frame * 2] = (v * 32_767.0).roundToInt().toShort()
            samples[frame * 2 + 1] = (v * 32_767.0).roundToInt().toShort()
        }
        return samples
    }

    /** 生成脉冲状信号：每 100 帧前 [dutyCycle] 比例处于 [highAmplitude]，其余接近零。 */
    private fun squareish16(
        frames: Int,
        highAmplitude: Double,
        dutyCycle: Double,
    ): ShortArray {
        val samples = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val v = if (frame % 100 < (100 * dutyCycle).toInt()) highAmplitude else 0.02
            samples[frame * 2] = (v * 32_767.0).roundToInt().toShort()
            samples[frame * 2 + 1] = (v * 32_767.0).roundToInt().toShort()
        }
        return samples
    }

    /** 由目标 RMS（dBFS，样本归一化到 /32768）反推正弦峰值幅度。 */
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

    private fun peakOf(samples: ShortArray): Double {
        var peak = 0.0
        for (s in samples) {
            val a = abs(s.toDouble() / 32_768.0)
            if (a > peak) peak = a
        }
        return peak
    }
}
