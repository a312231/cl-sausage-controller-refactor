package cn.niuyannet.kaochang.android.ui

import cn.niuyannet.kaochang.android.utils.LogUtils

object AdminOperationLogger {
    const val PAGE_SETTING = "管理界面"
    const val PAGE_PAN = "烤盘信息"
    const val PAGE_BOX = "烤肠箱信息"
    const val PAGE_CONFIG = "配置信息"
    const val PAGE_TEST = "测试下位机"

    const val RESULT_SUCCESS = "成功"
    const val RESULT_FAIL = "失败"
    const val RESULT_REJECT = "拒绝"
    const val RESULT_START = "开始"
    const val RESULT_DONE = "完成"
    const val RESULT_AUTO_EXIT = "自动退出"

    fun i(action: String, page: String, result: String, detail: String? = null) {
        LogUtils.i(buildMessage(action, page, result, detail))
    }

    fun w(action: String, page: String, result: String, detail: String? = null) {
        LogUtils.w(buildMessage(action, page, result, detail))
    }

    fun e(action: String, page: String, result: String, detail: String? = null) {
        LogUtils.e(buildMessage(action, page, result, detail))
    }

    fun buildMessageForTest(action: String, page: String, result: String, detail: String? = null): String {
        return buildMessage(action, page, result, detail)
    }

    fun mqttConfigDetailForTest(
        oldHost: String?,
        newHost: String?,
        oldPort: Int,
        newPort: Int,
        oldUsername: String?,
        newUsername: String?,
        oldPassword: String?,
        newPassword: String?
    ): String {
        return mqttConfigDetail(oldHost, newHost, oldPort, newPort, oldUsername, newUsername, oldPassword, newPassword)
    }

    fun mqttConfigDetail(
        oldHost: String?,
        newHost: String?,
        oldPort: Int,
        newPort: Int,
        oldUsername: String?,
        newUsername: String?,
        oldPassword: String?,
        newPassword: String?
    ): String {
        val passwordChanged = (oldPassword ?: "") != (newPassword ?: "")
        return "服务器地址=${oldHost.orEmpty()}->${newHost.orEmpty()}，" +
            "端口=$oldPort->$newPort，" +
            "账号=${oldUsername.orEmpty()}->${newUsername.orEmpty()}，" +
            "密码是否变更=${if (passwordChanged) "是" else "否"}"
    }

    private fun buildMessage(action: String, page: String, result: String, detail: String?): String {
        val normalizedDetail = detail?.takeIf { it.isNotBlank() }
        return buildString {
            append("【管理员操作】")
            append(action)
            append("：页面=")
            append(page)
            append("，操作结果=")
            append(result)
            if (normalizedDetail != null) {
                append("，")
                append(normalizedDetail)
            }
        }
    }
}
