package org.autojs.autodroid

import org.autojs.autodroid.update.AppUpdateFlow
import org.autojs.autodroid.update.AppUpdatePolicy
import org.autojs.autodroid.update.UpdateVerdict
import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {

    @Test fun higherRemoteVersionCodeMeansUpdateAvailable() {
        assertEquals(UpdateVerdict.UPDATE_AVAILABLE, AppUpdatePolicy.verdict("3821", 3820))
        assertEquals(UpdateVerdict.UP_TO_DATE, AppUpdatePolicy.verdict("3820", 3820))
        assertEquals(UpdateVerdict.UP_TO_DATE, AppUpdatePolicy.verdict("3819", 3820))
    }

    @Test fun unparsableRemoteVersionCodeIsUnknown() {
        assertEquals(UpdateVerdict.UNKNOWN_VERSION, AppUpdatePolicy.verdict(null, 3820))
        assertEquals(UpdateVerdict.UNKNOWN_VERSION, AppUpdatePolicy.verdict("", 3820))
        assertEquals(UpdateVerdict.UNKNOWN_VERSION, AppUpdatePolicy.verdict("abc", 3820))
        assertEquals(UpdateVerdict.UNKNOWN_VERSION, AppUpdatePolicy.verdict("-1.5x", 3820))
    }

    @Test fun floatingPointVersionCodeFallsBackToLong() {
        assertEquals(UpdateVerdict.UPDATE_AVAILABLE, AppUpdatePolicy.verdict("3821.0", 3820))
        assertEquals(3821L, AppUpdatePolicy.parseVersionCode("3821.0"))
    }

    @Test fun downloadUrlCarriesQueryTokenAndNormalizesBase() {
        val url = AppUpdateFlow.buildDownloadUrl("http://example.com:8000/", 7, "tok en")
        assertEquals("http://example.com:8000/packages/7/download?token=tok+en", url)
        val proxied = AppUpdateFlow.buildDownloadUrl("http://example.com/platform/api/", 7, "abc")
        assertEquals("http://example.com/platform/packages/7/download?token=abc", proxied)
    }
}
