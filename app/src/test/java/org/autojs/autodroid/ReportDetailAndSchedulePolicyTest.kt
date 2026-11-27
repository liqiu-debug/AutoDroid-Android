package org.autojs.autodroid

import org.autojs.autodroid.ui.ReportDetailActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDetailAndSchedulePolicyTest {
    @Test
    fun reportWebViewBlocksLocalFileAndContentSchemes() {
        assertTrue(ReportDetailActivity.isBlockedReportUrl("file"))
        assertTrue(ReportDetailActivity.isBlockedReportUrl("FILE"))
        assertTrue(ReportDetailActivity.isBlockedReportUrl("content"))
        assertTrue(ReportDetailActivity.isBlockedReportUrl("Content"))
        assertFalse(ReportDetailActivity.isBlockedReportUrl("https"))
        assertFalse(ReportDetailActivity.isBlockedReportUrl("http"))
        assertFalse(ReportDetailActivity.isBlockedReportUrl("about"))
        assertFalse(ReportDetailActivity.isBlockedReportUrl(null))
    }
}
