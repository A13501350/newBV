package dev.frost819.newbv.biliapi.http.entity.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 关注信息
 *
 * @param list 关注明细列表
 * @param reVersion
 * @param total 关注总数
 */
@Serializable
data class UserFollowData(
    val list: List<FollowedUser> = emptyList(),
    @SerialName("re_version")
    val reVersion: Int,
    val total: Int,
) {
    /**
     * 关注的用户信息
     *
     * @param mid 用户mid
     * @param attribute 关注属性 0：未关注 2：已关注 6：已互粉
     * @param mtime 关注对方时间 时间戳 互关后刷新
     * @param tag 分组id 默认分组：null 存在至少一个分组：array
     * @param special 特别关注标志 0：否 1：是
     * @param uname 用户昵称
     * @param face 用户头像url
     * @param sign 用户签名
     * @param officialVerify 认证信息
     * @param vip 会员信息
     * @param nftIcon
     * @param recReason
     * @param trackId
     */
    @Serializable
    data class FollowedUser(
        val mid: Long,
        val attribute: Int,
        val mtime: Int,
        val tag: List<Int>? = null,
        val special: Int,
        val uname: String,
        val face: String,
        val sign: String,
        @SerialName("official_verify")
        val officialVerify: OfficialVerify,
        val vip: Vip,
        @SerialName("nft_icon")
        val nftIcon: String,
        @SerialName("rec_reason")
        val recReason: String,
        @SerialName("track_id")
        val trackId: String,
    ) {
        /**
         * 会员信息
         *
         * @param vipType 会员类型 0：无 1：月度大会员 2：年度以上大会员
         * @param vipDueDate 会员到期时间 时间戳 毫秒
         * @param dueRemark
         * @param accessStatus
         * @param vipStatus 大会员状态 0：无 1：有
         * @param vipStatusWarn
         * @param themeType
         * @param label
         */
        @Serializable
        data class Vip(
            val vipType: Int,
            val vipDueDate: Long,
            val dueRemark: String,
            val accessStatus: Int,
            val vipStatus: Int,
            val vipStatusWarn: String,
            val themeType: Int,
            val label: dev.frost819.newbv.biliapi.http.entity.user.Vip.Label,
        )
    }
}

/**
 * 粉丝列表数据（`GET /x/relation/followers`）
 *
 * 响应结构与 [UserFollowData] 同构，但粉丝项通常不含 tag、nft_icon、rec_reason、
 * track_id 等字段（vip/official_verify 的字段形态也不稳定），故只建模下游消费的
 * mid/uname/face/sign，其余字段交由 `ignoreUnknownKeys` 忽略，避免反序列化失败。
 *
 * @param list 粉丝明细列表
 * @param reVersion
 * @param total 粉丝总数
 */
@Serializable
data class UserFollowersData(
    val list: List<Follower> = emptyList(),
    @SerialName("re_version")
    val reVersion: Int = 0,
    val total: Int = 0,
) {
    /**
     * 粉丝信息
     *
     * @param mid 粉丝用户 mid
     * @param uname 用户昵称
     * @param face 用户头像 url
     * @param sign 用户签名
     */
    @Serializable
    data class Follower(
        val mid: Long,
        val uname: String,
        val face: String,
        val sign: String = "",
    )
}

enum class FollowAction(
    val id: Int,
) {
    AddFollow(1),
    DelFollow(2),
    AddFollowQuietly(3),
    DelFollowQuietly(4),
    AddBlackList(5),
    DelBlackList(6),
    DelFan(7),
}

enum class FollowActionSource(
    val id: Int,
) {
    Space(11),
    Video(14),
    Article(115),
    Activity(222),
}
