package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemperatureRegisterWriteGuardTest {

    @Test
    fun productionCode_shouldOnlyWriteTemperatureRegistersInsideRawTemperatureWriter() {
        val sourceRoot = listOf(
            File("src/main/java"),
            File("app/src/main/java")
        ).first { it.exists() }
        val matches = sourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains("writeSingleRegister(action_address_") &&
                        line.contains("_temperature_write")
                    ) {
                        "${file.invariantSeparatorsPath}:${index + 1}:$line"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertEquals(
            "503/504/505 目标温度寄存器只能在 writeAreaTargetTemperatureRaw 内部最终落寄存器",
            3,
            matches.size
        )
        matches.forEach { match ->
            assertTrue(match, match.contains("KaoChangOperate.kt"))
        }
    }
}
