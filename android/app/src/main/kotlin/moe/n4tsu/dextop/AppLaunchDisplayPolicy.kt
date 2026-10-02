package moe.n4tsu.dextop

/** Chooses the display owner without redirecting pending Auto launches to the phone. */
internal object AppLaunchDisplayPolicy {
    fun select(
        autoOwned: Boolean,
        autoDisplayId: Int,
        phoneActive: Boolean,
        phoneDisplayId: Int,
        callerDisplayId: Int = 0,
    ): Int? {
        if (phoneActive && phoneDisplayId > 0 && callerDisplayId == phoneDisplayId) {
            return phoneDisplayId
        }
        if (autoOwned) return autoDisplayId.takeIf { it > 0 }
        return phoneDisplayId.takeIf { phoneActive && it > 0 }
    }
}
