package dev.frost819.newbv.app.ui.component.settings

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [UpdateStatus] 枚举的单元测试。
 */
class UpdateStatusTest {
    @Test
    fun enum_hasNineEntries() {
        assertThat(UpdateStatus.entries).hasSize(9)
    }

    @Test
    fun enum_containsExpectedStates() {
        assertThat(UpdateStatus.entries).containsAtLeast(
            UpdateStatus.UpdatingInfo,
            UpdateStatus.Ready,
            UpdateStatus.Downloading,
            UpdateStatus.Installing,
            UpdateStatus.NoAvailableUpdate,
            UpdateStatus.CheckError,
            UpdateStatus.DownloadError,
            UpdateStatus.InstallError,
            UpdateStatus.InstallPermissionRequired,
        )
    }
}
