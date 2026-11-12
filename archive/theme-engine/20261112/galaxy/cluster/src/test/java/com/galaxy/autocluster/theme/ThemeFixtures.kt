package com.galaxy.autocluster.theme

internal fun themeFixture(id: String = "custom", name: String = "自定义 & 日夜", revision: Long = 1) =
    ThemePackageCodec.create(ThemeMetadata(id, name, revision),
        "<scene designWidth=\"1920\" designHeight=\"532\"/>".toByteArray(),
        "<styles/>".toByteArray(), mapOf("assets/background.png" to byteArrayOf(1, 2, 3)))
