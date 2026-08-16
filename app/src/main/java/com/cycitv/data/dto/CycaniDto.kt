package com.cycitv.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val code: Int,
    val msg: String = "",
    val data: T? = null,
)

@Serializable
data class Pager(
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    val total: Int,
)

@Serializable
data class ZoneDto(
    val id: Int,
    val name: String,
    val sort: Int = 0,
)

@Serializable
data class VideoCardDto(
    @SerialName("video_id") val videoId: Long,
    @SerialName("zone_id") val zoneId: Int,
    val title: String,
    val description: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val remarks: String = "",
    val area: String = "",
    val year: Int? = null,
    val version: String = "",
    val score: Double? = null,
    val hits: Long = 0,
    val total: Int = 0,
    val tags: List<String> = emptyList(),
)

@Serializable
data class RecommendRowDto(
    val id: Int,
    val name: String = "",
    @SerialName("link_type") val linkType: String = "",
    @SerialName("zone_id") val zoneId: Int? = null,
    val videos: List<VideoCardDto> = emptyList(),
)

@Serializable
data class RecommendData(
    val list: List<RecommendRowDto> = emptyList(),
)

@Serializable
data class VideoListData(
    val list: List<VideoCardDto> = emptyList(),
    val pager: Pager? = null,
)

@Serializable
data class HotWordsData(
    val keywords: List<String> = emptyList(),
)

@Serializable
data class VideoDetailDto(
    val id: Long,
    val title: String,
    val description: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val area: String = "",
    val language: String = "",
    val year: Int? = null,
    val version: String = "",
    val score: Double? = null,
    val hits: Long = 0,
    val total: Int = 0,
    val director: List<String> = emptyList(),
    val actor: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val state: String = "",
    @SerialName("play_from") val playFrom: List<PlayFromDto> = emptyList(),
    @SerialName("watch_progress") val watchProgress: WatchProgressDto? = null,
)

@Serializable
data class PlayFromDto(
    val code: String,
    val title: String = "",
    val count: Int = 0,
)

@Serializable
data class WatchProgressDto(
    @SerialName("section_id") val sectionId: Long,
    @SerialName("section_title") val sectionTitle: String = "",
    val progress: Long = 0,
    val duration: Long = 0,
)

@Serializable
data class SectionDto(
    val id: Long,
    val title: String = "",
)

@Serializable
data class SectionListData(
    val list: List<SectionDto> = emptyList(),
    val pager: Pager? = null,
)

@Serializable
data class PlayUrlData(
    val name: String = "",
    val url: String = "",
)

@Serializable
data class WeekdayData(
    val list: List<WeekdayRowDto> = emptyList(),
)

@Serializable
data class WeekdayRowDto(
    val weekday: Int,
    val videos: List<VideoCardDto> = emptyList(),
)

@Serializable
data class RankData(
    val list: List<RankDto> = emptyList(),
)

@Serializable
data class RankDto(
    val id: Long,
    val name: String = "",
    val type: String = "",
    @SerialName("video_count") val videoCount: Int = 0,
)

@Serializable
data class RankVideosData(
    val list: List<VideoCardDto> = emptyList(),
)
