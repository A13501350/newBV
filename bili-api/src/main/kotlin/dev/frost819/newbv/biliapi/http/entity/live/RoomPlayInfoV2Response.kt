package dev.frost819.newbv.biliapi.http.entity.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 直播流地址 v2 响应。
 *
 * 端点: `GET /xlive/web-room/v2/index/getRoomPlayInfo`
 * 返回多协议（FLV/HLS）、多格式（FLV/TS/FMP4）、多编码（AVC/HEVC/AV1）的流地址。
 */
@Serializable
data class RoomPlayInfoV2Data(
    @SerialName("room_id")
    val roomId: Int,
    @SerialName("short_id")
    val shortId: Int,
    val uid: Long,
    @SerialName("live_status")
    val liveStatus: Int,
    @SerialName("playurl_info")
    val playUrlInfo: PlayUrlInfo,
)

@Serializable
data class PlayUrlInfo(
    @SerialName("playurl")
    val playUrl: PlayUrl,
    /**
     * 服务端判定客户端实际可得的画质（权威三元组，与选中编码节点的
     * current_qn/hdr_type/eotf 一致），用于校准当前档位标识。
     */
    @SerialName("expected_quality")
    val expectedQuality: ExpectedQuality? = null,
)

@Serializable
data class PlayUrl(
    val cid: Long,
    @SerialName("g_qn_desc")
    val qnDesc: List<QnDesc> = emptyList(),
    val stream: List<PlayStream> = emptyList(),
    /**
     * 杜比视界档位 qn（指向 [QnDesc.qn]），无杜比档位为 null。
     */
    @SerialName("dolby_qn")
    val dolbyQn: Int? = null,
)

@Serializable
data class QnDesc(
    val qn: Int,
    val desc: String = "",
    /**
     * HDR 档位徽标文案（如 "HDR"），SDR 档为空串。
     */
    @SerialName("hdr_desc")
    val hdrDesc: String = "",
    /**
     * HDR 类型标记，0 = SDR。
     */
    @SerialName("hdr_type")
    val hdrType: Int = 0,
    /**
     * 电光转换函数标记（如 PQ/HLG），0 = SDR。
     */
    val eotf: Int = 0,
)

@Serializable
data class PlayStream(
    @SerialName("protocol_name")
    val protocolName: String,
    val format: List<PlayFormat> = emptyList(),
)

@Serializable
data class PlayFormat(
    @SerialName("format_name")
    val formatName: String,
    val codec: List<PlayCodec> = emptyList(),
)

@Serializable
data class PlayCodec(
    @SerialName("codec_name")
    val codecName: String,
    @SerialName("current_qn")
    val currentQn: Int,
    @SerialName("accept_qn")
    val acceptQn: List<Int> = emptyList(),
    @SerialName("base_url")
    val baseUrl: String,
    @SerialName("url_info")
    val urlInfo: List<PlayUrlInfoItem> = emptyList(),
    @SerialName("media_info")
    val mediaInfo: MediaInfo = MediaInfo(),
    /**
     * 该编码对应的 HDR 档位 qn（HDR 档独立于 [acceptQn] 的 SDR 梯队），无 HDR 为 null。
     */
    @SerialName("hdr_qn")
    val hdrQn: Int? = null,
    /**
     * 杜比视界流标记，0 = 非杜比。
     */
    @SerialName("dolby_type")
    val dolbyType: Int = 0,
    /**
     * 当前流的 HDR 类型标记，0 = SDR。
     */
    @SerialName("hdr_type")
    val hdrType: Int = 0,
    /**
     * RFC 6381 编码串（如 `hvc1.1.6.L120`），用于本机解码能力判定。
     */
    @SerialName("video_codecs")
    val videoCodecs: CodecSpec? = null,
    /**
     * 色彩信息：SDR 为 bt709/none，HDR 为 bt2020 + 动态元数据类型。
     */
    @SerialName("video_color_info")
    val videoColorInfo: VideoColorInfo? = null,
)

@Serializable
data class CodecSpec(
    val base: String = "",
    val ext: List<String> = emptyList(),
)

@Serializable
data class VideoColorInfo(
    val colorspace: String = "",
    /**
     * 电光转换函数标记，0 = SDR，1 = PQ(HDR10)，2 = HLG。
     */
    val eotf: Int = 0,
    @SerialName("metadata_types")
    val metadataTypes: List<String> = emptyList(),
)

@Serializable
data class ExpectedQuality(
    val qn: Int = 0,
    @SerialName("hdr_type")
    val hdrType: Int = 0,
    val eotf: Int = 0,
)

@Serializable
data class PlayUrlInfoItem(
    val host: String,
    val extra: String,
    @SerialName("is_timeout")
    val isTimeout: Boolean = false,
)

@Serializable
data class MediaInfo(
    val height: Int = 0,
    val width: Int = 0,
    @SerialName("realtime_avg_bw")
    val realtimeAvgBw: Long = 0,
)
