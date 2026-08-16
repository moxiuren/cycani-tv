package com.cycitv.data.repository

import com.cycitv.data.api.CycaniApi
import com.cycitv.data.dto.RecommendRowDto
import com.cycitv.data.dto.VideoCardDto
import com.cycitv.data.dto.ZoneDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HomeRepository(
    private val api: CycaniApi,
    private val scope: CoroutineScope,
) {
    private val cacheMutex = Mutex()
    private var cachedRecommend: List<RecommendRowDto>? = null
    private var cachedZones: List<ZoneDto>? = null

    suspend fun recommend(force: Boolean = false): List<RecommendRowDto> = cacheMutex.withLock {
        if (!force && cachedRecommend != null) return cachedRecommend!!
        val data = api.recommend()
        cachedRecommend = data.list
        return data.list
    }

    suspend fun zones(force: Boolean = false): List<ZoneDto> = cacheMutex.withLock {
        if (!force && cachedZones != null) return cachedZones!!
        val zones = api.zones()
        cachedZones = zones
        return zones
    }
}
