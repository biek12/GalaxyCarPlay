package com.ecarx.autoservice.input

import org.junit.Assert.*
import org.junit.Test

class ClusterMenuInputTest {
    @Test fun shortReleaseOnceRepeatedDownAndDuplicateSnapshotsDoNotSwitchTwice(){
        val input=ClusterMenuInput()
        assertFalse(input.accept("one",1,0,6,100))
        assertFalse(input.accept("one",2,0,6,120))
        assertTrue(input.accept("one",3,1,6,160))
        assertFalse(input.accept("one",3,1,6,160))
        assertFalse(input.accept("one",4,1,6,180))
    }
    @Test fun longCancelOrNewGenerationCannotBecomeShortPress(){
        val input=ClusterMenuInput()
        input.accept("one",1,0,6,100)
        assertFalse(input.accept("one",2,1,6,900))
        input.accept("one",3,0,6,1000);input.accept("one",4,2,6,1010)
        assertFalse(input.accept("one",5,1,6,1020))
        input.accept("one",6,0,6,1100)
        assertFalse(input.accept("two",1,1,6,1120))
        assertFalse(input.accept("two",2,0,18,1200))
        assertFalse(input.accept("two",3,1,18,1250))
    }
}
