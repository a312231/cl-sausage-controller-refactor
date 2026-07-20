package cn.niuyannet.kaochang.android.services

internal class InMemoryLowToHighDemandStateStoreForTest : LowToHighDemandStateStore {
    private var state = LowToHighDemandState()

    override fun load(): LowToHighDemandLoadResult = LowToHighDemandLoadResult(
        LowToHighDemandLoadStatus.PRESENT,
        state.copy(blockedFrontPositions = state.blockedFrontPositions.toMutableSet())
    )

    override fun saveDurable(state: LowToHighDemandState): Boolean {
        this.state = state.copy(blockedFrontPositions = state.blockedFrontPositions.toMutableSet())
        return true
    }

    override fun clearDurable(): Boolean {
        state = LowToHighDemandState()
        return true
    }
}
