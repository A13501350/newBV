package dev.frost819.newbv.player.audio

import dev.frost819.newbv.player.entity.AudioBalanceLevel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PCM 级自适应响度归一器（音量均衡核心 DSP）。
 *
 * 纯 JVM 实现，不依赖 Android / Media3，便于单元测试。
 *
 * 算法：
 * 1. 起播以保守增益（[Params.startupGainDb]）开始，避免首块音频被过度放大；
 * 2. 前 [Params.calibrationSignalSec] 秒有效信号用于校准节目 RMS；
 * 3. 之后以 [Params.programIntegrationSec] 时间常数持续积分跟踪节目响度，
 *    使增益向「节目 RMS → 目标响度（[TARGET_RMS_DB] dBFS）」收敛；
 * 4. 增益钳制在档位区间 `[Params.minGainDb, Params.maxGainDb]` 内，
 *    档位越高允许的调节幅度越大；
 * 5. 逐块计算峰值保护增益（[LIMITER_CEILING] / peak），防止放大后削波；
 * 6. 增益变化按 attack/release 时间常数一阶平滑，防止 pumping。
 *
 * 线程模型：[processPcm16] / [processPcmFloat] / [reset] / [configure] 必须固定在
 * 单一音频处理线程调用；[level] 为 volatile 写，可在任意线程调用，档位变化在
 * 下一个音频块生效并重置自适应状态。
 *
 * @param initialLevel 初始档位
 */
class VolumeNormalizer(initialLevel: AudioBalanceLevel = AudioBalanceLevel.OFF) {
    /** 当前档位。volatile 写入，任意线程；变更在下一个音频块生效。 */
    @Volatile
    var level: AudioBalanceLevel = initialLevel

    /** 实际驱动 [params] 的档位（用于检测 [level] 的跨线程变更）。 */
    private var activeLevel: AudioBalanceLevel = initialLevel

    /** 当前档位的调节参数。 */
    private var params: Params = paramsFor(initialLevel)

    private var sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ
    private var channelCount: Int = DEFAULT_CHANNEL_COUNT

    // --- 自适应状态（configure/reset/档位切换时重置） ---

    /** 校准/跟踪的有效信号累计时长（秒）。 */
    private var signalAccumulatedSec = 0.0

    /** 校准窗口内的信号平方和累计。 */
    private var calibrationSumSquares = 0.0

    /** 校准窗口内的样本数累计。 */
    private var calibrationSampleCount = 0L

    /** 节目平均功率；null 表示仍在校准窗口内。 */
    private var programMeanSquare: Double? = null

    /** 当前线性增益。 */
    private var currentGain = 1.0f

    /**
     * 应用音频格式并重置自适应状态。
     *
     * 切流 / 编码格式变化时由调用方（[VolumeBalanceAudioProcessor.onConfigure]）调用。
     *
     * @param sampleRateHz 采样率（Hz），非法值回退默认
     * @param channelCount 声道数，非法值回退默认
     */
    fun configure(
        sampleRateHz: Int,
        channelCount: Int,
    ) {
        this.sampleRateHz = sampleRateHz.coerceAtLeast(MIN_SAMPLE_RATE_HZ)
        this.channelCount = channelCount.coerceAtLeast(MIN_CHANNEL_COUNT)
        resetAdaptiveState()
    }

    /**
     * 处理一块 PCM 16-bit 样本（原地缩放）。
     *
     * 档位为 [AudioBalanceLevel.OFF] 时不修改样本（直通）。
     *
     * @param samples 交织 PCM 样本数组（原地修改）
     * @param sampleCount 参与处理的样本数（含全部声道），必须 ≤ [samples].size
     */
    fun processPcm16(
        samples: ShortArray,
        sampleCount: Int,
    ) {
        if (sampleCount <= 0) return
        syncLevel()
        if (level == AudioBalanceLevel.OFF) return

        val p = params
        var sumSquares = 0.0
        var peakAbs = 0.0
        for (i in 0 until sampleCount) {
            val v = samples[i].toDouble() / PCM_16_FULL_SCALE
            val a = abs(v)
            if (a > peakAbs) peakAbs = a
            sumSquares += v * v
        }

        val gain = updateGain(sumSquares, sampleCount, peakAbs, p)

        for (i in 0 until sampleCount) {
            val scaled = (samples[i].toFloat() * gain).toInt()
            samples[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * 处理一块 PCM float 样本（原地缩放），约定同 [processPcm16]。
     *
     * @param samples 交织 PCM 样本数组（取值范围 [-1, 1]，原地修改）
     * @param sampleCount 参与处理的样本数（含全部声道），必须 ≤ [samples].size
     */
    fun processPcmFloat(
        samples: FloatArray,
        sampleCount: Int,
    ) {
        if (sampleCount <= 0) return
        syncLevel()
        if (level == AudioBalanceLevel.OFF) return

        val p = params
        var sumSquares = 0.0
        var peakAbs = 0.0
        for (i in 0 until sampleCount) {
            val f = samples[i].toDouble()
            val a = abs(f)
            if (a > peakAbs) peakAbs = a
            sumSquares += f * f
        }

        val gain = updateGain(sumSquares, sampleCount, peakAbs, p)

        for (i in 0 until sampleCount) {
            samples[i] = (samples[i] * gain).coerceIn(-1.0f, 1.0f)
        }
    }

    /** 重置自适应状态（校准进度与增益回到起播值），音频格式保持不变。 */
    fun reset() {
        resetAdaptiveState()
    }

    /**
     * 当前状态快照，供测试与调试信息观测。
     *
     * 注意：与处理线程并发调用时读到的可能是中间态（字段非原子一致），
     * 观测用途下可接受。
     */
    fun snapshot(): Stats =
        Stats(
            level = level,
            gainDb = linearToDb(currentGain.toDouble()).toFloat(),
            programRmsDb = programMeanSquare?.let { linearToDb(sqrt(it)).toFloat() },
            calibrated = programMeanSquare != null,
        )

    /**
     * 档位被外部切换时换参数并重置自适应状态，保证「切换即时生效」。
     * 在每个音频块入口调用。
     */
    private fun syncLevel() {
        val external = level
        if (external != activeLevel) {
            activeLevel = external
            params = paramsFor(external)
            resetAdaptiveState()
        }
    }

    /**
     * 计算本块增益并推进自适应状态，返回本块应使用的线性增益。
     */
    private fun updateGain(
        sumSquares: Double,
        sampleCount: Int,
        peakAbs: Double,
        p: Params,
    ): Float {
        val meanSquare = (sumSquares / sampleCount.toDouble()).coerceAtLeast(0.0)
        val rms = sqrt(meanSquare)
        val rmsDb = if (rms > 0.0) linearToDb(rms) else SILENT_RMS_DB

        // 按帧数推导本块时长：样本数含全部声道，单块时长 = 帧数 / 采样率
        val frameCount = max(1, sampleCount / channelCount)
        val dtSec = frameCount.toDouble() / sampleRateHz.toDouble()

        if (rmsDb > p.silenceGateDb) {
            signalAccumulatedSec += dtSec
            val tracking = programMeanSquare
            if (tracking == null) {
                // 校准窗口：累计平方和，窗口走满得到节目平均功率
                calibrationSumSquares += sumSquares
                calibrationSampleCount += sampleCount.toLong()
                if (signalAccumulatedSec >= p.calibrationSignalSec && calibrationSampleCount > 0L) {
                    programMeanSquare = calibrationSumSquares / calibrationSampleCount.toDouble()
                }
            } else {
                // 跟踪窗口：一阶积分缓慢跟随节目响度，避免句内 pumping
                val alpha = alphaForTimeConstant(dtSec, p.programIntegrationSec)
                programMeanSquare = tracking + alpha * (meanSquare - tracking)
            }
        }

        val desiredGainDb =
            if (programMeanSquare == null) {
                p.startupGainDb
            } else {
                val programRms = sqrt((programMeanSquare ?: MIN_MEAN_SQUARE).coerceAtLeast(MIN_MEAN_SQUARE))
                (TARGET_RMS_DB - linearToDb(programRms)).coerceIn(p.minGainDb, p.maxGainDb)
            }
        val desiredGainLinear = dbToLinear(desiredGainDb)

        // 峰值保护：放大后峰值不得超过 LIMITER_CEILING，防止削波
        val peakSafeGain =
            if (peakAbs > 0.0) {
                (LIMITER_CEILING / peakAbs).toFloat()
            } else {
                Float.POSITIVE_INFINITY
            }

        val desired = min(desiredGainLinear, peakSafeGain)
        // 增益下降（attack）比上升（release）更快，瞬态响度变化优先防削波
        val tau = if (desired < currentGain) p.gainAttackSec else p.gainReleaseSec
        val gainAlpha = alphaForTimeConstant(dtSec, tau).toFloat()
        currentGain = (currentGain + gainAlpha * (desired - currentGain)).coerceIn(p.minGainLinear, p.maxGainLinear)
        if (peakSafeGain < currentGain) {
            currentGain = peakSafeGain
        }
        return currentGain
    }

    /** 重置校准进度与增益：非 OFF 档回到起播保守增益，OFF 档回到 1.0。 */
    private fun resetAdaptiveState() {
        signalAccumulatedSec = 0.0
        calibrationSumSquares = 0.0
        calibrationSampleCount = 0L
        programMeanSquare = null
        currentGain = if (activeLevel == AudioBalanceLevel.OFF) 1.0f else params.startupGainLinear
    }

    /** 单个档位的调节参数（dB 域定义，线性域缓存）。 */
    private data class Params(
        val startupGainDb: Double,
        val maxGainDb: Double,
        val minGainDb: Double,
        val silenceGateDb: Double,
        val calibrationSignalSec: Double,
        val programIntegrationSec: Double,
        val gainAttackSec: Double,
        val gainReleaseSec: Double,
        val startupGainLinear: Float,
        val maxGainLinear: Float,
        val minGainLinear: Float,
    )

    /** 处理状态快照。 */
    data class Stats(
        /** 当前档位。 */
        val level: AudioBalanceLevel,
        /** 当前应用的增益（dB）。 */
        val gainDb: Float,
        /** 已校准的节目 RMS（dBFS）；校准完成前为 null。 */
        val programRmsDb: Float?,
        /** 是否已完成节目响度校准。 */
        val calibrated: Boolean,
    )

    private companion object {
        /** 归一目标响度（RMS，dBFS）。 */
        const val TARGET_RMS_DB = -20.0

        /** 峰值上限（线性），防止放大后削波。 */
        const val LIMITER_CEILING = 0.98

        /** 平均功率下限，防止 log10(0)。 */
        const val MIN_MEAN_SQUARE = 1.0e-12

        /** 静音块的 RMS 表记（dB）。 */
        const val SILENT_RMS_DB = -120.0

        /** PCM 16-bit 满量程。 */
        const val PCM_16_FULL_SCALE = 32768.0

        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_CHANNEL_COUNT = 2
        const val MIN_SAMPLE_RATE_HZ = 8_000
        const val MIN_CHANNEL_COUNT = 1

        /**
         * 各档位参数。
         *
         * 起播稍安静，先用前几秒有效信号校准，再缓慢漂移——
         * 语音不会在句内 pumping；档位越高跟踪越积极、允许的增益范围越大。
         */
        private fun paramsFor(level: AudioBalanceLevel): Params {
            val startupGainDb =
                when (level) {
                    AudioBalanceLevel.HIGH -> -3.0
                    AudioBalanceLevel.MEDIUM -> -1.5
                    AudioBalanceLevel.LOW -> -0.5
                    AudioBalanceLevel.OFF -> 0.0
                }
            val maxGainDb =
                when (level) {
                    AudioBalanceLevel.HIGH -> 2.5
                    AudioBalanceLevel.MEDIUM -> 2.0
                    AudioBalanceLevel.LOW -> 1.0
                    AudioBalanceLevel.OFF -> 0.0
                }
            val minGainDb =
                when (level) {
                    AudioBalanceLevel.HIGH -> -16.0
                    AudioBalanceLevel.MEDIUM -> -12.0
                    AudioBalanceLevel.LOW -> -8.0
                    AudioBalanceLevel.OFF -> 0.0
                }
            val silenceGateDb =
                when (level) {
                    AudioBalanceLevel.HIGH -> -55.0
                    AudioBalanceLevel.MEDIUM -> -53.0
                    AudioBalanceLevel.LOW -> -50.0
                    AudioBalanceLevel.OFF -> -120.0
                }
            val calibrationSignalSec =
                when (level) {
                    AudioBalanceLevel.HIGH -> 1.5
                    AudioBalanceLevel.MEDIUM -> 2.0
                    AudioBalanceLevel.LOW -> 2.5
                    AudioBalanceLevel.OFF -> 0.0
                }
            val programIntegrationSec =
                when (level) {
                    AudioBalanceLevel.HIGH -> 6.0
                    AudioBalanceLevel.MEDIUM -> 8.0
                    AudioBalanceLevel.LOW -> 10.0
                    AudioBalanceLevel.OFF -> 0.0
                }
            val attackSec =
                when (level) {
                    AudioBalanceLevel.HIGH -> 1.5
                    AudioBalanceLevel.MEDIUM -> 2.0
                    AudioBalanceLevel.LOW -> 3.0
                    AudioBalanceLevel.OFF -> 0.05
                }
            val releaseSec =
                when (level) {
                    AudioBalanceLevel.HIGH -> 5.0
                    AudioBalanceLevel.MEDIUM -> 6.0
                    AudioBalanceLevel.LOW -> 8.0
                    AudioBalanceLevel.OFF -> 1.20
                }

            return Params(
                startupGainDb = startupGainDb,
                maxGainDb = maxGainDb,
                minGainDb = minGainDb,
                silenceGateDb = silenceGateDb,
                calibrationSignalSec = calibrationSignalSec,
                programIntegrationSec = programIntegrationSec,
                gainAttackSec = attackSec,
                gainReleaseSec = releaseSec,
                startupGainLinear = dbToLinear(startupGainDb),
                maxGainLinear = dbToLinear(maxGainDb),
                minGainLinear = dbToLinear(minGainDb),
            )
        }

        private fun dbToLinear(db: Double): Float {
            if (!db.isFinite()) return 1.0f
            return 10.0.pow(db / 20.0).toFloat()
        }

        private fun linearToDb(linear: Double): Double = 20.0 * log10(linear)

        /** 一阶低通系数：时间常数 [tauSec] 内响应 [dtSec] 秒。 */
        private fun alphaForTimeConstant(
            dtSec: Double,
            tauSec: Double,
        ): Double {
            if (!dtSec.isFinite() || !tauSec.isFinite()) return 1.0
            if (dtSec <= 0.0) return 0.0
            if (tauSec <= 0.0) return 1.0
            return (1.0 - exp(-dtSec / tauSec)).coerceIn(0.0, 1.0)
        }
    }
}
