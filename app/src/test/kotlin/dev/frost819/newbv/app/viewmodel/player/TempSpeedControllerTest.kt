package dev.frost819.newbv.app.viewmodel.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [TempSpeedController] 的单元测试。
 *
 * 验证长按确认键临时倍速状态机语义：start 生效并记录原速、重复 start 去重、
 * stop 恢复原速、未 start 时 stop 无副作用、写速度 lambda 异常安全。
 * 通过捕获 lambda 调用序列断言，不依赖播放器实现。
 */
class TempSpeedControllerTest {

    /** 写速度 lambda 收到的速度序列。 */
    private val writtenSpeeds = mutableListOf<Float>()

    /** 提示回调收到的文本序列。 */
    private val tips = mutableListOf<String>()

    /** 用户设定速度（readBaseSpeed 的返回值），测试中可修改。 */
    private var baseSpeed = 1f

    /** 置 true 时写速度 lambda 抛异常，模拟播放器未就绪。 */
    private var failWrite = false

    /** 构造接入捕获 lambda 的被测对象。 */
    private fun newController(): TempSpeedController =
        TempSpeedController(
            readBaseSpeed = { baseSpeed },
            writeEngineSpeed = { speed ->
                if (failWrite) throw IllegalStateException("videoPlayer 未初始化")
                writtenSpeeds += speed
            },
            onTip = { tips += it },
        )

    @Test
    fun `startTempSpeed doubles base speed records base and shows tip`() {
        // Given
        val controller = newController()
        baseSpeed = 1.25f

        // When
        controller.startTempSpeed()

        // Then: 引擎速度翻倍、提示实际速度、会话激活
        assertThat(writtenSpeeds).containsExactly(2.5f)
        assertThat(tips).containsExactly("2.5x")
        assertThat(controller.isTempSpeedActive).isTrue()
    }

    @Test
    fun `startTempSpeed caps boosted speed at max limit`() {
        // Given
        val controller = newController()
        baseSpeed = 2f

        // When
        controller.startTempSpeed()

        // Then: 2 倍为 4.0，超过上限封顶 3.0
        assertThat(writtenSpeeds).containsExactly(3f)
        assertThat(tips).containsExactly("3.0x")
    }

    @Test
    fun `startTempSpeed dedupes repeated calls from long press repeat events`() {
        // Given
        val controller = newController()
        baseSpeed = 1f

        // When: 长按确认键会补发多条 isLongPress KeyDown，只应生效一次
        controller.startTempSpeed()
        controller.startTempSpeed()
        controller.startTempSpeed()

        // Then
        assertThat(writtenSpeeds).containsExactly(2f).inOrder()
        assertThat(controller.isTempSpeedActive).isTrue()
    }

    @Test
    fun `stopTempSpeed restores recorded base speed even if user speed changed`() {
        // Given
        val controller = newController()
        baseSpeed = 1.25f
        controller.startTempSpeed()

        // When: 激活期间用户改设定速度，恢复基准仍是进入时记录值
        baseSpeed = 1.5f
        controller.stopTempSpeed()

        // Then
        assertThat(writtenSpeeds).containsExactly(2.5f, 1.25f).inOrder()
        assertThat(controller.isTempSpeedActive).isFalse()
    }

    @Test
    fun `stopTempSpeed without start has no side effects`() {
        // Given
        val controller = newController()

        // When
        controller.stopTempSpeed()

        // Then
        assertThat(writtenSpeeds).isEmpty()
        assertThat(tips).isEmpty()
        assertThat(controller.isTempSpeedActive).isFalse()
    }

    @Test
    fun `startTempSpeed stays inactive when write lambda throws`() {
        // Given: 模拟播放器未就绪，写速度抛异常
        val controller = newController()
        failWrite = true

        // When
        controller.startTempSpeed()

        // Then: 不激活、不提示；stop 也无副作用（不会误写速度）
        assertThat(controller.isTempSpeedActive).isFalse()
        assertThat(tips).isEmpty()
        controller.stopTempSpeed()
        assertThat(writtenSpeeds).isEmpty()
    }

    @Test
    fun `startTempSpeed succeeds on retry after write failure recovers`() {
        // Given
        val controller = newController()
        failWrite = true
        controller.startTempSpeed()

        // When: 播放器就绪后再次长按
        failWrite = false
        controller.startTempSpeed()

        // Then
        assertThat(writtenSpeeds).containsExactly(2f)
        assertThat(controller.isTempSpeedActive).isTrue()
    }

    @Test
    fun `stopTempSpeed deactivates even when restore write throws`() {
        // Given
        val controller = newController()
        baseSpeed = 1f
        controller.startTempSpeed()

        // When: 恢复时写速度抛异常（播放器已释放等）
        failWrite = true
        controller.stopTempSpeed()

        // Then: 激活态必须退出以还原确认键 KeyUp 语义
        assertThat(controller.isTempSpeedActive).isFalse()
    }
}
