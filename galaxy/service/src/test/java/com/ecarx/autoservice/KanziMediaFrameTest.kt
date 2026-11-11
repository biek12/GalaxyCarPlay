package com.ecarx.autoservice

import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
class KanziMediaFrameTest {
    private fun media()=Bundle().apply{
        putString("title","Song");putString("artist","Artist");putBoolean("playing",true)
        putLong("elapsed",1600);putLong("duration",5000);putLong("receivedAt",1000)
        putStringArrayList("lyrics",arrayListOf("previous","current","next","after"));putLongArray("lyricTimes",longArrayOf(0,1000,2000,3000))
    }
    @Test fun pausedPositionDoesNotAdvanceAndFourLinesUseCurrentSecond(){
        val value=media().apply{putBoolean("playing",false)}
        val bytes=KanziMediaFrame.encode(value,true,UUID(10,2),1,10000,5000)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1600,b.getLong(44));assertEquals(17000,b.getLong(36));assertEquals(1,b.getShort(10).toInt()and 3)
        var offset=72;val rows=(0..5).map{index->val length=b.getShort(60+index*2).toInt()and 65535;String(bytes,offset,length,Charsets.UTF_8).also{offset+=length}}
        assertEquals(listOf("Song","Artist","previous","current","next","after"),rows)
    }
    @Test fun coverKeyChangesBetweenSessionsAndDisabledFrameRestores(){
        assertNotEquals(KanziMediaFrame.coverKey(media(),UUID(10,2)),KanziMediaFrame.coverKey(media(),UUID(11,2)))
        val bytes=KanziMediaFrame.encode(media(),false,UUID(10,2),1,10000,5000)
        assertEquals(0,ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt())
        assertEquals(72,bytes.size)
    }
}
