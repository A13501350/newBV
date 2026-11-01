package dev.frost819.newbv.biliapi.repositories

import bilibili.app.interfaces.v1.HistoryGrpcKt
import bilibili.app.interfaces.v1.cursor
import bilibili.app.interfaces.v1.cursorV2Req
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.user.HistoryData
import dev.frost819.newbv.biliapi.entity.user.HistoryItem
import dev.frost819.newbv.biliapi.http.BiliHttpApi

class HistoryRepository(
    private val authRepository: AuthRepository,
    private val channelRepository: ChannelRepository,
) {
    private val historyStub
        get() =
            runCatching {
                HistoryGrpcKt.HistoryCoroutineStub(channelRepository.requireDefaultChannel())
            }.getOrNull()

    suspend fun getHistories(
        cursor: Long,
        preferApiType: ApiType,
    ): HistoryData =
        when (preferApiType) {
            ApiType.Web -> {
                val data =
                    BiliHttpApi
                        .getHistories(
                            viewAt = cursor,
                        ).getResponseData()
                HistoryData.fromHistoryResponse(data)
            }

            ApiType.App -> {
                val reply =
                    historyStub?.cursorV2(
                        cursorV2Req {
                            this.cursor =
                                cursor {
                                    max = cursor
                                }
                            business = "archive"
                        },
                    )
                HistoryData.fromHistoryResponse(reply!!)
            }
        }

    /**
     * 删除一条观看历史记录。
     *
     * 删除端点 `/x/v2/history/delete` 仅有 Web 通道，App API 模式下同样走 Web 通道。
     *
     * @param item 待删除的历史条目
     * @throws IllegalArgumentException 当条目无法编码出合法 kid（类型未知或 PGC 缺少 season id）时抛出
     * @throws IllegalStateException 当 B 站返回失败状态时抛出
     */
    suspend fun deleteHistory(item: HistoryItem) {
        val kid =
            HistoryItem.encodeDeleteKid(item)
                ?: throw IllegalArgumentException("无法删除该类型的历史记录：type=${item.type}")
        val successMessage =
            BiliHttpApi.deleteHistory(
                kid = kid,
                csrf = authRepository.biliJct.orEmpty(),
            )
        check(successMessage.first) { successMessage.second.ifBlank { "删除历史记录失败" } }
    }
}
