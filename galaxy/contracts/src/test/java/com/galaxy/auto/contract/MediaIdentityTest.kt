package com.galaxy.auto.contract

import org.junit.Assert.*
import org.junit.Test

class MediaIdentityTest {
    @Test fun sourceAndProviderIdSeparateSameNamedSongs(){
        val a=MediaIdentity.key("native","app-a","Song","Artist","Album","1")
        assertNotEquals(a,MediaIdentity.key("native","app-b","Song","Artist","Album","1"))
        assertNotEquals(a,MediaIdentity.key("native","app-a","Song","Artist","Album","2"))
        assertEquals(a,MediaIdentity.key("native","app-a","Song","Artist","Album","1"))
    }
    @Test fun longLineWaitsThenStopsAtTailAndPauseCanReusePosition(){
        assertEquals(0f,LyricScroll.offset(100f,799),.001f)
        assertEquals(28f,LyricScroll.offset(100f,1800),.001f)
        assertEquals(100f,LyricScroll.offset(100f,10000),.001f)
        assertEquals(0f,LyricScroll.offset(-5f,1000),.001f)
    }
}
