package cn.niuyannet.kaochang.android.updates

import com.jws.systemfor3rdservice.ISystemFor3RDService
import org.junit.Assert.assertEquals
import org.junit.Test

class JwsSilentInstallerTest {

    @Test
    fun aidlTransactionCode_matchesVendorStub() {
        val field = ISystemFor3RDService.Stub::class.java
            .getDeclaredField("TRANSACTION_jwsSilentInstallApp")
            .apply { isAccessible = true }

        assertEquals(41, field.getInt(null))
        assertEquals(41, JwsSilentInstaller.TRANSACTION_JWS_SILENT_INSTALL_APP)
    }

    @Test
    fun aidlDescriptor_matchesVendorService() {
        val field = ISystemFor3RDService::class.java
            .getDeclaredField("DESCRIPTOR")
            .apply { isAccessible = true }

        assertEquals("com.jws.systemfor3rdservice.ISystemFor3RDService", field.get(null))
    }
}
