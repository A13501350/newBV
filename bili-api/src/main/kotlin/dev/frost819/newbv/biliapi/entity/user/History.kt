package dev.frost819.newbv.biliapi.entity.user

import bilibili.app.interfaces.v1.CursorItem

// TODO 暂时仅解析 UGC 和 PGC
data class HistoryData(
    val cursor: Long,
    val data: List<HistoryItem>,
) {
    companion object {
        fun fromHistoryResponse(data: dev.frost819.newbv.biliapi.http.entity.history.HistoryData) =
            HistoryData(
                cursor = data.cursor.viewAt,
                data =
                    data.list
                        .filter { it.history.business == "archive" || it.history.business == "pgc" }
                        .map { HistoryItem.fromHistoryItem(it) },
            )

        fun fromHistoryResponse(data: bilibili.app.interfaces.v1.CursorV2Reply) =
            HistoryData(
                cursor = data.cursor.max,
                data =
                    data.itemsList
                        .filter {
                            it.cardItemCase == CursorItem.CardItemCase.CARD_UGC ||
                                it.cardItemCase == CursorItem.CardItemCase.CARD_OGV
                        }.map { HistoryItem.fromHistoryItem(it) },
            )
    }
}

data class HistoryItem(
    val oid: Long,
    val bvid: String,
    val cid: Long,
    val kid: Long,
    val epid: Int?,
    val seasonId: Int?,
    val title: String,
    val cover: String,
    val author: String,
    val mid: Long?,
    val duration: Int,
    val progress: Int,
    val type: HistoryItemType,
) {
    companion object {
        /**
         * 编码删除观看历史所需的 `kid` 参数。
         *
         * 删除端点 `/x/v2/history/delete` 的 `kid` 格式：UGC 为 `arc_` + 稿件 avid（[HistoryItem.oid]），
         * PGC 为 `pgc_` + 番剧 season id（[HistoryItem.seasonId]）。
         * 注意 [HistoryItem.kid] 是历史记录 id（App 接口）或未解析占位（Web 接口），
         * 与删除参数的 kid 含义不同，不能直接使用。
         *
         * @param item 历史条目
         * @return 编码后的 kid 字符串；类型未知或 PGC 缺少 season id 时返回 null（无法构造合法 kid）
         */
        fun encodeDeleteKid(item: HistoryItem): String? =
            when (item.type) {
                HistoryItemType.Archive -> "arc_${item.oid}"
                HistoryItemType.Pgc -> item.seasonId?.let { "pgc_$it" }
                HistoryItemType.Unknown -> null
            }

        fun fromHistoryItem(item: dev.frost819.newbv.biliapi.http.entity.history.HistoryItem) =
            HistoryItem(
                oid = item.history.oid,
                bvid = item.history.bvid,
                cid = item.history.cid,
                kid = 0,
                epid = item.history.epid,
                seasonId = null,
                title = item.title,
                cover = item.cover,
                author = item.authorName,
                mid = item.authorMid,
                duration = item.duration,
                progress = item.progress,
                type =
                    when (item.history.business) {
                        "archive" -> HistoryItemType.Archive
                        "pgc" -> HistoryItemType.Pgc
                        else -> HistoryItemType.Unknown
                    },
            )

        @Suppress("RemoveRedundantQualifierName")
        fun fromHistoryItem(item: bilibili.app.interfaces.v1.CursorItem) =
            HistoryItem(
                oid = item.oid,
                bvid =
                    when (item.cardItemCase) {
                        CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.bvid
                        CursorItem.CardItemCase.CARD_OGV -> ""
                        else -> ""
                    },
                cid =
                    when (item.cardItemCase) {
                        CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.cid
                        CursorItem.CardItemCase.CARD_OGV -> 0
                        else -> 0
                    },
                kid = item.kid,
                epid = null,
                // App 历史接口不提供 season id（item.kid 是历史记录 id，非 season id）
                seasonId = null,
                title = item.title,
                cover =
                    when (item.cardItemCase) {
                        CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.cover
                        CursorItem.CardItemCase.CARD_OGV -> item.cardOgv.cover
                        else -> ""
                    },
                author =
                    when (item.cardItemCase) {
                        CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.name
                        CursorItem.CardItemCase.CARD_OGV -> ""
                        else -> ""
                    },
                mid =
                    when (item.cardItemCase) {
                        CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.mid
                        CursorItem.CardItemCase.CARD_OGV -> null
                        else -> null
                    },
                duration =
                    when (item.cardItemCase) {
                        CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.duration.toInt()
                        CursorItem.CardItemCase.CARD_OGV -> item.cardOgv.duration.toInt()
                        else -> 0
                    },
                progress =
                    when (item.cardItemCase) {
                        CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.progress.toInt()
                        CursorItem.CardItemCase.CARD_OGV -> item.cardOgv.progress.toInt()
                        else -> 0
                    },
                type =
                    when (item.cardItemCase) {
                        CursorItem.CardItemCase.CARD_UGC -> HistoryItemType.Archive
                        CursorItem.CardItemCase.CARD_OGV -> HistoryItemType.Pgc
                        else -> HistoryItemType.Unknown
                    },
            )
    }
}

enum class HistoryItemType {
    Unknown,
    Archive,
    Pgc,
}
