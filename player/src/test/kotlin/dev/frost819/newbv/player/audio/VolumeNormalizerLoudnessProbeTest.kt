package dev.frost819.newbv.player.audio

import dev.frost819.newbv.player.entity.AudioBalanceLevel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [VolumeNormalizer] 响度探针（调试分支专用调参工具，非普通单测）。
 *
 * 用 5 种响度场景 × 3 个档位跑 40 秒收敛仿真，输出每组合的
 * 终态增益 / 输出响度 / 校准状态摘要表，供档位参数（起播增益、
 * 钳制范围、attack/release、校准窗）调优时对照参考。
 *
 * 同时断言基本健康约束，作为参数改动的回归守卫：
 * - 输出峰值不超过限幅天花板（防削波）
 * - 终态增益落在合理区间（防失控）
 * - 相邻块增益变化有界（防 pumping）
 */
class VolumeNormalizerLoudnessProbeTest {
    @Test
    fun `loudness probe across scenes and levels stays within healthy bounds`() {
        val report = StringBuilder("\n[probe] ── 音量均衡收敛仿真（40s @1s 块，48kHz 立体声）──\n")

        for (scene in scenes) {
            for (level in listOf(AudioBalanceLevel.LOW, AudioBalanceLevel.MEDIUM, AudioBalanceLevel.HIGH)) {
                // Given：按场景生成 40 秒信号，逐块喂入并记录增益轨迹
                val normalizer = VolumeNormalizer(level)
                var maxOutputPeak = 0.0
                var maxGainStepDb = 0.0
                var prevGainDb = normalizer.snapshot().gainDb
                var lastOutputRmsDb = -120.0

                val blockCount = DURATION_SEC * SAMPLE_RATE / FRAMES_PER_BLOCK
                repeat(blockCount) { block ->
                    val blockSamples = scene.generate(block, FRAMES_PER_BLOCK)
                    normalizer.processPcm16(blockSamples, blockSamples.size)
                    val gainDb = normalizer.snapshot().gainDb
                    maxGainStepDb = maxOf(maxGainStepDb, abs(gainDb - prevGainDb))
                    prevGainDb = gainDb
                    maxOutputPeak = maxOf(maxOutputPeak, peakOf(blockSamples))
                    lastOutputRmsDb = rmsDbOf(blockSamples)
                }

                // Then：健康约束
                val finalGainDb = normalizer.snapshot().gainDb
                assertTrue(maxOutputPeak <= LIMITER_CEILING + PEAK_TOLERANCE) {
                    "[probe] ${scene.name}/$level: output peak $maxOutputPeak exceeds limiter"
                }
                assertTrue(finalGainDb in MIN_SANE_GAIN_DB..MAX_SANE_GAIN_DB) {
                    "[probe] ${scene.name}/$level: final gain $finalGainDb dB out of sane range"
                }
                assertTrue(maxGainStepDb <= MAX_GAIN_STEP_DB) {
                    "[probe] ${scene.name}/$level: gain jumped $maxGainStepDb dB in one block (pumping)"
                }

                report.appendLine(
                    "[probe] scene=${scene.name.padEnd(20)} level=${level.name.padEnd(6)} " +
                        "finalGain=${"%+.2f".format(Locale.US, finalGainDb)}dB " +
                        "outRms=${"%.1f".format(Locale.US, lastOutputRmsDb)}dBFS " +
                        "peak=$maxOutputPeak maxStep=${"%.2f".format(Locale.US, maxGainStepDb)}dB " +
                        "calibrated=${normalizer.snapshot().calibrated}",
                )
            }
        }
        println(report)
    }

    /** 响度场景：按块号生成 PCM 16-bit 立体声样本。 */
    private interface Scene {
        val name: String

        /** @param blockIndex 块号（每块 1 秒） @param frames 块内帧数 */
        fun generate(
            blockIndex: Int,
            frames: Int,
        ): ShortArray
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val FRAMES_PER_BLOCK = 48_000
        const val DURATION_SEC = 40
        const val LIMITER_CEILING = 0.98
        const val PEAK_TOLERANCE = 0.001
        const val MIN_SANE_GAIN_DB = -40f
        const val MAX_SANE_GAIN_DB = 3.0f
        const val MAX_GAIN_STEP_DB = 3.0

        /** 正弦生成：指定 RMS（dBFS）与频率。 */
        fun sine(
            frames: Int,
            rmsDb: Double,
            frequency: Double = 440.0,
        ): ShortArray {
            val amplitude = 10.0.pow(rmsDb / 20.0) * sqrt(2.0)
            val samples = ShortArray(frames * CHANNELS)
            for (frame in 0 until frames) {
                val v = amplitude * sin(2.0 * PI * frequency * frame / SAMPLE_RATE)
                samples[frame * CHANNELS] = (v * 32_767.0).roundToInt().toShort()
                samples[frame * CHANNELS + 1] = (v * 32_767.0).roundToInt().toShort()
            }
            return samples
        }

        fun silence(frames: Int): ShortArray = ShortArray(frames * CHANNELS)

        val scenes =
            listOf(
                // 安静人声：期望被放大到档位上限
                object : Scene {
                    override val name = "quiet-speech(-30dB)"

                    override fun generate(
                        blockIndex: Int,
                        frames: Int,
                    ) = sine(frames, -30.0)
                },
                // 常规内容：接近目标响度，期望小幅度调节
                object : Scene {
                    override val name = "normal(-18dB)"

                    override fun generate(
                        blockIndex: Int,
                        frames: Int,
                    ) = sine(frames, -18.0)
                },
                // 响音乐：期望被衰减
                object : Scene {
                    override val name = "loud-music(-8dB)"

                    override fun generate(
                        blockIndex: Int,
                        frames: Int,
                    ) = sine(frames, -8.0)
                },
                // 动态交替：响度每 5 秒在 -30/-10 间切换，观察跟踪速度与 pumping
                object : Scene {
                    override val name = "dynamic(-30/-10 alt)"

                    override fun generate(
                        blockIndex: Int,
                        frames: Int,
                    ) = sine(frames, if ((blockIndex / 5) % 2 == 0) -30.0 else -10.0)
                },
                // 语音含停顿：8s 信号 + 4s 静音循环，观察静音门限与校准保持
                object : Scene {
                    override val name = "speech-with-gaps"

                    override fun generate(
                        blockIndex: Int,
                        frames: Int,
                    ): ShortArray = if (blockIndex % 12 < 8) sine(frames, -15.0) else silence(frames)
                },
            )
    }
}

/** 计算样本峰值（归一化到 /32768）。 */
private fun peakOf(samples: ShortArray): Double {
    var peak = 0.0
    for (s in samples) {
        val a = abs(s.toDouble() / 32_768.0)
        if (a > peak) peak = a
    }
    return peak
}

/** 计算样本 RMS（dBFS，归一化到 /32768）。 */
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
