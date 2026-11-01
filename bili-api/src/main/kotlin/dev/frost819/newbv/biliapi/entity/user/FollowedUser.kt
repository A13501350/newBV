package dev.frost819.newbv.biliapi.entity.user

data class FollowedUser(
    val mid: Long,
    val name: String,
    val avatar: String,
    val sign: String,
) {
    companion object {
        fun fromHttpFollowedUser(
            followedUser: dev.frost819.newbv.biliapi.http.entity.user.UserFollowData.FollowedUser,
        ) = FollowedUser(
            mid = followedUser.mid,
            name = followedUser.uname,
            avatar = followedUser.face,
            sign = followedUser.sign,
        )

        /**
         * 从 HTTP 粉丝数据构建领域实体。
         *
         * 粉丝接口与关注接口返回同构的 mid/name/avatar/sign 信息，故复用 [FollowedUser]。
         *
         * @param follower HTTP 层粉丝数据。
         * @return 粉丝领域实体。
         */
        fun fromHttpFollower(
            follower: dev.frost819.newbv.biliapi.http.entity.user.UserFollowersData.Follower,
        ) = FollowedUser(
            mid = follower.mid,
            name = follower.uname,
            avatar = follower.face,
            sign = follower.sign,
        )
    }
}
