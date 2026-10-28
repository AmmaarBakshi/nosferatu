package dev.nosferatu.app.ui.run

import dev.nosferatu.app.map.CorrectionMarker
import dev.nosferatu.app.map.MapOverlayState
import dev.nosferatu.app.tracking.LiveState
import dev.nosferatu.engine.matching.MatchResult
import dev.nosferatu.engine.matching.MatchState
import dev.nosferatu.engine.model.GpsFix
import dev.nosferatu.engine.track.CorrectionEvent
import dev.nosferatu.engine.track.PositionSource

/** Pure mapping from tracking state to what the map and panels show; kept free of Compose so it is unit-tested. */
object RunPresentation {
    /** The overlay before a run: just the raw fix and its accuracy. */
    fun idleOverlay(fix: GpsFix?): MapOverlayState =
        MapOverlayState(position = fix?.position, uncertaintyM = fix?.accuracyM, headingDeg = fix?.bearingDeg)

    /** The overlay during a run. Raw fixes, corrections and the matched way are diagnostics and hidden by default. */
    fun runOverlay(live: LiveState, diagnostics: Boolean): MapOverlayState {
        val snap = live.snapshot
        return MapOverlayState(
            position = snap?.position,
            uncertaintyM = snap?.uncertaintyM,
            headingDeg = snap?.headingDeg,
            trail = live.trail,
            rawFixes = if (diagnostics) live.rawFixes else emptyList(),
            matchedRoad = if (diagnostics) live.matchedRoad else emptyList(),
            corrections = if (diagnostics) corrections(live.events) else emptyList(),
            diagnostics = diagnostics,
        )
    }

    fun corrections(events: List<CorrectionEvent>): List<CorrectionMarker> = events.mapNotNull { e ->
        when (e.kind) {
            CorrectionEvent.Kind.Rejected -> CorrectionMarker(e.position, CorrectionMarker.Kind.Rejected)
            CorrectionEvent.Kind.DownWeighted -> CorrectionMarker(e.position, CorrectionMarker.Kind.DownWeighted)
            else -> null
        }
    }

    /** A short label for the map-match state, and the way's name when there is one. */
    fun matchLabel(match: MatchResult?): Pair<String, String?> = when (match?.state) {
        null -> "No map" to null
        MatchState.Matched -> "On way" to match.wayName
        MatchState.Ambiguous -> "Ambiguous" to match.wayName
        MatchState.OffNetwork -> "Unmapped path" to null
        MatchState.NoCoverage -> "Outside map" to null
    }

    /** Whether the position is currently being estimated without GPS. */
    fun isDeadReckoning(source: PositionSource?): Boolean =
        source == PositionSource.DeadReckoning || source == PositionSource.Held

    /** Average pace over the run in metres per second, or null before there is enough to say. */
    fun averageSpeed(distanceM: Double, movingTimeMs: Long): Double? =
        if (distanceM < 20 || movingTimeMs < 10_000) null else distanceM / (movingTimeMs / 1000.0)
}
