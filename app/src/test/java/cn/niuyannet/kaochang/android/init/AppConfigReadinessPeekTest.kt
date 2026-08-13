package cn.niuyannet.kaochang.android.init

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AppConfigReadinessPeekTest {

    @Test
    fun peekInitializedAppConfig_doesNotInitializeDefaultsOrPersistence() {
        val field = AppConfig::class.java.getDeclaredField("appConfigBean").apply { isAccessible = true }
        synchronized(AppConfig) {
            val original = field.get(AppConfig)
            try {
                field.set(AppConfig, null)
                assertNull(AppConfig.peekInitializedAppConfig())

                val initialized = AppConfigBean()
                field.set(AppConfig, initialized)
                assertSame(initialized, AppConfig.peekInitializedAppConfig())
            } finally {
                field.set(AppConfig, original)
            }
        }
    }
}
