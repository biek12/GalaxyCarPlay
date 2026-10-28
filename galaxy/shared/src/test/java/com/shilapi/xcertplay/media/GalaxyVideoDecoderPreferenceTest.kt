package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

/** 只验证候选与尝试策略；不创建codec或改变Surface。 */
class GalaxyVideoDecoderPreferenceTest {
    private val preferred = "OMX.qcom.video.decoder.avc"
    private val lowLatency = "c2.qti.avc.decoder.low_latency"
    private val software = "c2.android.avc.decoder"

    @Test fun noPreferencePreservesEveryOriginalAttemptIncludingMtkModes() {
        val ordinary = videoDecoderAttempts(60, software, lowLatency,
            MtkDecoderMode.LOW_LATENCY_NO_REORDER)
        val explicitNull = videoDecoderAttempts(60, software, lowLatency,
            MtkDecoderMode.LOW_LATENCY_NO_REORDER, preferredHardwareDecoder = null)
        assertEquals(ordinary, explicitNull)
        assertEquals(DecoderAttempt(lowLatency, tuned = true, operatingRate = 60), ordinary.first())
        assertEquals(DecoderAttempt(software, tuned = false), ordinary.last())
    }

    @Test fun explicitHardwarePrecedesOriginalTunedAndMinimalAttempts() {
        val ordinary = videoDecoderAttempts(60, software, lowLatency)
        val actual = videoDecoderAttempts(60, software, lowLatency,
            preferredHardwareDecoder = preferred)
        assertEquals(listOf(
            DecoderAttempt(preferred, tuned = true, operatingRate = 60),
            DecoderAttempt(preferred, tuned = true),
            DecoderAttempt(preferred, tuned = false),
        ), actual.take(3))
        assertEquals(ordinary, actual.drop(3))
    }

    @Test fun noOperatingRateMeansExactlyTwoPreferredAttemptsBeforeDefaults() {
        val ordinary = videoDecoderAttempts(0, software)
        val actual = videoDecoderAttempts(0, software, preferredHardwareDecoder = preferred)
        assertEquals(listOf(DecoderAttempt(preferred, tuned = true),
            DecoderAttempt(preferred, tuned = false)), actual.take(2))
        assertEquals(ordinary, actual.drop(2))
    }

    @Test fun preferredLowLatencyDecoderIsNotRepeatedInOneConfigureCycle() {
        val actual = videoDecoderAttempts(60, software, lowLatency,
            preferredHardwareDecoder = lowLatency)
        assertEquals(actual.size, actual.distinct().size)
        assertEquals(3, actual.count { it.codecName == lowLatency })
        assertEquals(DecoderAttempt(software, tuned = false), actual.last())
    }

    @Test fun candidateMustBeAvailableHardwareAndNotSoftware() {
        val candidates = listOf(
            VideoDecoderCandidate(preferred, true, false, false),
            VideoDecoderCandidate(software, false, true, false),
            VideoDecoderCandidate("ambiguous", true, true, false),
        )
        assertEquals(preferred, VideoDecoderSelection.availableHardware(candidates, preferred))
        assertNull(VideoDecoderSelection.availableHardware(candidates, software))
        assertNull(VideoDecoderSelection.availableHardware(candidates, "ambiguous"))
        assertNull(VideoDecoderSelection.availableHardware(candidates, "missing"))
        assertNull(VideoDecoderSelection.availableHardware(candidates, null))
    }

    @Test fun unavailablePreferenceCannotCreateAnExtraAttempt() {
        val requested = VideoDecoderSelection.availableHardware(emptyList(), preferred)
        assertEquals(videoDecoderAttempts(30, software),
            videoDecoderAttempts(30, software, preferredHardwareDecoder = requested))
    }

    @Test fun selectedHardwareKeepsExistingOrderingOfOtherAvailableCandidates() {
        val c2 = VideoDecoderCandidate("c2.qti.avc.decoder", true, false, false)
        val omx = VideoDecoderCandidate(preferred, true, false, false)
        val soft = VideoDecoderCandidate(software, false, true, false)
        assertEquals(listOf(omx, c2, soft),
            VideoDecoderSelection.ordered(listOf(c2, soft, omx), false, preferred))
        assertEquals(listOf(soft, c2, omx),
            VideoDecoderSelection.ordered(listOf(c2, soft, omx), true, preferred))
    }

    @Test fun successfulPreferredAttemptKeepsPreferenceAndAllFailureRetainsItsDiagnosis() {
        assertEquals(preferred, nextLowLatencyDecoder(preferred, DecoderAttempt(preferred, tuned = false)))
        assertEquals(preferred, nextLowLatencyDecoder(preferred, used = null))
        assertNull(nextLowLatencyDecoder(preferred, DecoderAttempt(null, tuned = true)))
    }
}
