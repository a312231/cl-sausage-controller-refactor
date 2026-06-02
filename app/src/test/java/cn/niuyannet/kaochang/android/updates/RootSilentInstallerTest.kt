package cn.niuyannet.kaochang.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSilentInstallerTest {

    @Test
    fun isRootAvailable_requiresUidZero() {
        assertTrue(RootSilentInstaller.isRootAvailableOutput("uid=0(root) gid=0(root)"))
        assertFalse(RootSilentInstaller.isRootAvailableOutput("uid=2000(shell) gid=2000(shell)"))
        assertFalse(RootSilentInstaller.isRootAvailableOutput(""))
    }

    @Test
    fun buildInstallScript_stagesApkInstallsStartsTwiceThenMonkeyAndCleansUp() {
        val script = RootSilentInstaller.buildInstallScript(
            sourceApkPath = "/sdcard/Android/data/cn.niuyannet.kaochang.android/files/updates/app-update-130.apk",
            targetVersionCode = 130,
            packageName = "cn.niuyannet.kaochang.android",
            launcherComponent = "cn.niuyannet.kaochang.android/.ui.StartInitActivity"
        )

        assertTrue(script.contains("rm -f /data/local/tmp/leye-update-*.apk"))
        assertTrue(script.contains("cp '/sdcard/Android/data/cn.niuyannet.kaochang.android/files/updates/app-update-130.apk' '/data/local/tmp/leye-update-130.apk'"))
        assertTrue(script.contains("chmod 0644 '/data/local/tmp/leye-update-130.apk'"))
        assertTrue(script.contains("pm install -r -g --user 0 '/data/local/tmp/leye-update-130.apk'"))
        assertTrue(script.contains("sleep 2"))
        assertTrue(script.contains("am start -n 'cn.niuyannet.kaochang.android/.ui.StartInitActivity' || true"))
        assertTrue(script.contains("sleep 3"))
        assertTrue(script.contains("sleep 5"))
        assertTrue(script.contains("monkey -p 'cn.niuyannet.kaochang.android' -c android.intent.category.LAUNCHER 1 || true"))
        assertTrue(script.contains("rm -f '/data/local/tmp/leye-update-130.apk'"))
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("'a'\"'\"'b'", RootSilentInstaller.shellQuote("a'b"))
    }
}
