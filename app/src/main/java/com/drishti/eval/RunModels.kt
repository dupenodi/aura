package com.drishti.eval

import kotlinx.serialization.Serializable

@Serializable
data class RunManifest(
    val runId: String,
    val task: String,
    val startedAtMs: Long,
    var endedAtMs: Long? = null,
    var status: String = "running",
    var stepCount: Int = 0,
    var llmCalls: Int = 0,
    var treeChangeCount: Int = 0,
    var stuck: Boolean = false,
    var error: String? = null,
    val dirName: String,
)

@Serializable
data class TraceEvent(
    val tsMs: Long,
    val type: String,
    val step: Int? = null,
    val packageName: String? = null,
    val fingerprint: String? = null,
    val fingerprintBefore: String? = null,
    val fingerprintAfter: String? = null,
    val treeChanged: Boolean? = null,
    val actionableCount: Int? = null,
    val treeFile: String? = null,
    val shotFile: String? = null,
    val provider: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolOk: Boolean? = null,
    val message: String? = null,
    val durationMs: Long? = null,
)
