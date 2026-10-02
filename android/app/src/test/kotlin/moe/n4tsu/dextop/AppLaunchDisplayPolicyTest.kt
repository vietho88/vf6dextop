package moe.n4tsu.dextop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLaunchDisplayPolicyTest {
    @Test fun phoneCallerPrefersAutoWhenAutoOwnsSession() {
        assertEquals(7, AppLaunchDisplayPolicy.select(true, 7, true, 4))
        assertEquals(7, AppLaunchDisplayPolicy.select(true, 7, false, -1))
    }

    @Test fun callerInsideActivePhoneDesktopRetainsItsDisplay() {
        assertEquals(4, AppLaunchDisplayPolicy.select(true, 7, true, 4, callerDisplayId = 4))
        assertEquals(4, AppLaunchDisplayPolicy.select(true, -1, true, 4, callerDisplayId = 4))
    }

    @Test fun pendingOrStaleAutoNeverFallsBackToPhone() {
        assertNull(AppLaunchDisplayPolicy.select(true, 0, true, 4))
        assertNull(AppLaunchDisplayPolicy.select(true, -1, true, 4))
    }

    @Test fun unrelatedDisplayCallerDoesNotOverrideAutoOwnership() {
        assertEquals(7, AppLaunchDisplayPolicy.select(true, 7, true, 4, callerDisplayId = 9))
    }

    @Test fun inactivePhoneCallerDoesNotOverrideAutoOwnership() {
        assertEquals(7, AppLaunchDisplayPolicy.select(true, 7, false, 4, callerDisplayId = 4))
    }

    @Test fun withoutAutoUsesOnlyAnActiveValidPhoneDesktop() {
        assertEquals(4, AppLaunchDisplayPolicy.select(false, 7, true, 4))
        assertNull(AppLaunchDisplayPolicy.select(false, 7, false, 4))
        assertNull(AppLaunchDisplayPolicy.select(false, 7, true, 0))
        assertNull(AppLaunchDisplayPolicy.select(false, 7, true, -1))
    }

    @Test fun defaultDisplayIsNeverConsideredAPhoneDesktopCaller() {
        assertNull(AppLaunchDisplayPolicy.select(true, -1, true, 0, callerDisplayId = 0))
    }
}
