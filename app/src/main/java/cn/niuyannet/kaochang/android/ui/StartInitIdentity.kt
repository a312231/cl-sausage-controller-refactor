package cn.niuyannet.kaochang.android.ui

object StartInitIdentity {
    private val activationCodePattern = Regex("^KC-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    fun normalize(input: String): String {
        return input.trim()
    }

    fun isActivationCode(input: String): Boolean {
        return activationCodePattern.matches(normalize(input).uppercase())
    }
}
