package cn.niuyannet.kaochang.scheduler

data class StrategyId(val value: String) {
    init {
        require(value.matches(VALID_VALUE)) {
            "Strategy id must use 3-64 lowercase ASCII letters, digits, '.', '_' or '-': $value"
        }
    }

    private companion object {
        val VALID_VALUE = Regex("[a-z0-9][a-z0-9._-]{2,63}")
    }
}

interface SellCandidateStrategy {
    val id: StrategyId

    fun selectPosition(input: SellCandidateInput): Int?
}
