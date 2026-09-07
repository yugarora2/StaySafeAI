package com.staysafeai.app.analyzers

import com.staysafeai.app.models.IRAnalysis
import com.staysafeai.app.models.IRSource

class IRClusterAnalyzer {

    fun analyze(sources: List<IRSource>): IRAnalysis {
        val pointSources = sources.filter { it.type == IRSource.Type.POINT_SOURCE }
        val diffuseSources = sources.filter { it.type == IRSource.Type.DIFFUSE }
        val pulsingSources = sources.filter { it.isPulsing }

        // Camera IR illuminators pulse at ~10Hz and are point sources
        val cameraSources = sources.filter { source ->
            source.type == IRSource.Type.POINT_SOURCE &&
            source.isPulsing &&
            source.pulseFrequency in 8.0..12.0
        }

        // TV/screens: diffuse, non-pulsing
        val screenSources = sources.filter { source ->
            source.type == IRSource.Type.DIFFUSE && !source.isPulsing
        }

        val cameraConfidence = when {
            cameraSources.size >= 2 -> 0.92f // Two IR illuminators = stereo camera
            cameraSources.size == 1 -> 0.75f
            pointSources.isNotEmpty() -> 0.45f
            else -> 0.0f
        }

        return IRAnalysis(
            totalSources = sources.size,
            pointSourceCount = pointSources.size,
            diffuseSourceCount = diffuseSources.size,
            cameraSuspectCount = cameraSources.size,
            screenSuspectCount = screenSources.size,
            cameraConfidence = cameraConfidence,
            dominantSource = sources.maxByOrNull { it.maxBrightness },
            hasCameraPattern = cameraSources.isNotEmpty(),
            hasScreenPattern = screenSources.isNotEmpty()
        )
    }
}
