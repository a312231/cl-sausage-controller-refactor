package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBoxPositionPolicyTest {
    @Test
    fun `4F source box policy allows only reachable boxes`() {
        assertFalse(SourceBoxPositionPolicy.isValidSourceBox(1))
        assertTrue(SourceBoxPositionPolicy.isValidSourceBox(2))
        assertTrue(SourceBoxPositionPolicy.isValidSourceBox(10))
        assertFalse(SourceBoxPositionPolicy.isValidSourceBox(11))
        assertFalse(SourceBoxPositionPolicy.isValidSourceBox(12))
        assertTrue(SourceBoxPositionPolicy.isValidSourceBox(13))
        assertTrue(SourceBoxPositionPolicy.isValidSourceBox(21))
        assertFalse(SourceBoxPositionPolicy.isValidSourceBox(22))
    }
}
