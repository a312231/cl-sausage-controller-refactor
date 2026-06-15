package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumableStateResponseParserTest {

    @Test
    fun parse_shouldBuildDisplayTextWithNullableThresholdAndUpdateTime() {
        val result = ConsumableStateResponseParser.parse(
            """
            {
              "code": 200,
              "data": [
                {
                  "consumableType": "STICK",
                  "capacity": 500,
                  "thresholdValue": null,
                  "currentValue": 480,
                  "enabled": 1,
                  "updateTime": "2026-06-15 14:49:26",
                  "lastHandledAt": null
                },
                {
                  "consumableType": "WASTE",
                  "capacity": null,
                  "thresholdValue": null,
                  "currentValue": 14,
                  "enabled": 0,
                  "updateTime": "2026-06-15 14:49:26",
                  "lastHandledAt": "2026-06-15 13:00:00"
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(result.success)
        assertEquals(
            "竹签 480/500（预警启用）  弃肠 14（阈值未设，预警停用）  更新 14:49",
            ConsumableStateResponseParser.buildDisplayText(result.states)
        )
    }

    @Test
    fun parse_shouldReturnUnavailableForInvalidJson() {
        val result = ConsumableStateResponseParser.parse("ok")

        assertFalse(result.success)
        assertEquals("耗材状态暂不可用", ConsumableStateResponseParser.buildDisplayText(result.states))
    }
}
