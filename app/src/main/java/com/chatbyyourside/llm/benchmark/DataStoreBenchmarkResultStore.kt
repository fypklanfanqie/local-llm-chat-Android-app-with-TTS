package com.chatbyyourside.llm.benchmark

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 基准结果专用 DataStore（文件 benchmark_store.preferences_pb）。 */
private val Context.benchmarkResultStore by preferencesDataStore(name = "benchmark_store")

/**
 * DataStore + JSON 基准结果持久化（Task 5 Step 4）。
 *
 * 键 = `scenario.storageKey|quadrant|deviceFingerprint|configFingerprint`（四象限同场景分键归档，
 * 保证 CPU/GPU、思考开/关的结果互不覆盖）；值 = kotlinx.serialization JSON
 * （ignoreUnknownKeys + encodeDefaults，与遥测持久化风格一致）。
 *
 * 契约约束：仅持久化 [BenchmarkScenarioResult.coolRun]=true 的结果；热态/噪声结果不落盘。
 * 覆盖式更新：同键再 save 直接覆盖旧值。
 *
 * 注意：契约 [BenchmarkResultStore.load] 无象限维度，故依次尝试四个象限键返回首个命中
 * （同一场景+指纹下一般仅一份冷态结果；Task 7 接入 UI 如需按象限查询可在此之上加带象限的键）。
 */
class DataStoreBenchmarkResultStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : BenchmarkResultStore {

    private val context = context.applicationContext

    override suspend fun save(result: BenchmarkScenarioResult) {
        // 契约：仅存冷态结果。
        if (!result.coolRun) {
            Log.w(TAG, "热态/非冷态结果不落盘: scenario=${result.scenario.storageKey} coolRun=false")
            return
        }
        val key = keyOf(result.scenario, result.deviceFingerprint, result.configFingerprint, result.quadrant)
        context.benchmarkResultStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = json.encodeToString(result)
        }
        Log.i(TAG, "基准结果已持久化: $key")
    }

    override suspend fun load(
        scenario: InferenceBenchmarkScenario,
        deviceFingerprint: String,
        configFingerprint: String,
    ): BenchmarkScenarioResult? {
        val prefs = context.benchmarkResultStore.data.first()
        for (quadrant in InferenceBackendQuadrant.entries) {
            val key = keyOf(scenario, deviceFingerprint, configFingerprint, quadrant)
            val raw = prefs[stringPreferencesKey(key)] ?: continue
            return runCatching { json.decodeFromString<BenchmarkScenarioResult>(raw) }
                .getOrElse {
                    Log.w(TAG, "基准结果 JSON 解析失败（忽略）: $key ${it.message}")
                    null
                }
        }
        return null
    }

    private fun keyOf(
        scenario: InferenceBenchmarkScenario,
        deviceFingerprint: String,
        configFingerprint: String,
        quadrant: InferenceBackendQuadrant?,
    ): String {
        val quadrantPart = quadrant?.storageKey ?: "UNKNOWN"
        return "${scenario.storageKey}|$quadrantPart|$deviceFingerprint|$configFingerprint"
    }

    companion object {
        private const val TAG = "DataStoreBenchmarkResultStore"
    }
}
