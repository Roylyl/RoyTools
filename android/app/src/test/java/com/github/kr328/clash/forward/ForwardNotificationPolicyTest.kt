package com.github.kr328.clash.forward
import org.junit.Assert.*
import org.junit.Test
class ForwardNotificationPolicyTest {
    @Test fun removedNotificationMayRepeatSameText(){
        val cache=ForwardDeduplicator()
        cache.accepted("wechat:1","相同文字")
        assertTrue(cache.duplicate("wechat:1","相同文字"))
        cache.removed("wechat:1")
        assertFalse(cache.duplicate("wechat:1","相同文字"))
    }
    @Test fun updatesAndOtherKeysAreNotSuppressed(){
        val cache=ForwardDeduplicator();cache.accepted("a","first")
        assertFalse(cache.duplicate("a","second"));assertFalse(cache.duplicate("b","first"))
    }
    @Test fun chinesePayloadLeavesRoomForWorkData(){
        assertTrue(forwardPayload("中".repeat(3900)).toByteArray(Charsets.UTF_8).size<=6000)
    }
    @Test fun emojiBoundaryDoesNotCreateBrokenSurrogate(){
        val payload=forwardPayload("a".repeat(1999)+"😀")
        assertEquals(1999,payload.length)
        assertEquals("hello",forwardPayload("hello"))
    }
}
