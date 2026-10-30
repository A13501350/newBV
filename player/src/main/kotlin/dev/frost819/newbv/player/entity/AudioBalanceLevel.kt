package dev.frost819.newbv.player.entity

/**
 * 音量均衡档位。
 *
 * 对应 [dev.frost819.newbv.player.audio.VolumeNormalizer] 的自适应响度归一强度：
 * 档位越高，允许的增益调节范围越大，对响度差异大的内容压制/提升越明显。
 *
 * 持久化存储枚举名（[name]），由 [fromName] 还原；未知值回退 [OFF]。
 */
enum class AudioBalanceLevel(
    val displayName: String,
) {
    OFF("关闭"),
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    ;

    companion object {
        /**
         * 从持久化的枚举名还原档位。
         *
         * @param name 存储的枚举名，null 或未知值（历史/脏数据）回退 [OFF]
         */
        fun fromName(name: String?): AudioBalanceLevel =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}
