package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyMediaSdkTest {
    @Test fun l6CurrentChannelLoadsSdkFromVerifiedFactoryMusicPackage() {
        assertEquals(listOf("com.flyme.auto.music"),
            GalaxyMediaSdk.packages("l6", GalaxyMediaReportingChannel.Mode.CURRENT))
    }

    @Test fun l7AndExplicitCompatibilityChannelsKeepTheirOwnSelection() {
        assertTrue(GalaxyMediaSdk.packages("l7", GalaxyMediaReportingChannel.Mode.CURRENT).isEmpty())
        for (channel in GalaxyMediaReportingChannel.Mode.entries.filter { it != GalaxyMediaReportingChannel.Mode.CURRENT }) {
            assertEquals(channel.sdkPackages, GalaxyMediaSdk.packages("l6", channel))
        }
    }
}
