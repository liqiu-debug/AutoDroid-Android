package org.autojs.autodroid

import com.google.gson.Gson
import org.autojs.autodroid.net.CaseItem
import org.autojs.autodroid.net.CaseStepItem
import org.autojs.autodroid.net.PaginatedCases
import org.autojs.autodroid.net.ScriptBundle
import org.autojs.autodroid.run.RunBridge
import org.autojs.autodroid.ui.StepAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 步骤预览/结果解析对显式 JSON null 的容错回归。
 *
 * 事故：用例预览崩溃 ClassCastException: JsonNull cannot be cast to JsonObject——
 * 步骤 JSON 的 platform_overrides 为 {"android": null} 时，Gson 的
 * getAsJsonObject("android") 直接强转崩。UI/解析层取值一律走类型安全访问器。
 */
class CaseStepPreviewNullTest {

    private val gson = Gson()

    private fun stepFrom(json: String): CaseStepItem = gson.fromJson(json, CaseStepItem::class.java)

    @Test fun androidOverrideNullNeverCrashesPreview() {
        val step = stepFrom(
            """{"order":1,"action":"click","args":{"text":null},
               "platform_overrides":{"android":null,"ios":{"selector":"允许"}},
               "timeout":10,"error_strategy":"ABORT","retry_count":0}"""
        )
        // android 覆盖为 null 时不能崩，也应回退到 args.text；text 同为 null → 只剩动作名
        assertEquals("点击", StepAdapter.stepSummary(step))
    }

    @Test fun androidOverrideNullIgnoresIosOnlySelector() {
        val step = stepFrom(
            """{"order":2,"action":"click",
               "platform_overrides":{"android":null,"ios":{"selector":"允许"}},
               "args":{}}"""
        )
        // ios 的定位不能被当作 android 的展示
        assertEquals("点击", StepAdapter.stepSummary(step))
    }

    @Test fun androidOverrideObjectShowsLocator() {
        val step = stepFrom(
            """{"order":3,"action":"click",
               "platform_overrides":{"android":{"selector":"登录","by":"text"}},
               "args":{}}"""
        )
        assertEquals("点击 · text=登录", StepAdapter.stepSummary(step))
    }

    @Test fun platformOverridesMissingOrArgsNullNeverCrashes() {
        assertEquals("点击", StepAdapter.stepSummary(stepFrom("""{"order":4,"action":"click"}""")))
        assertEquals(
            "输入 · 登录",
            StepAdapter.stepSummary(
                stepFrom("""{"order":5,"action":"input","args":{"text":"登录"},"platform_overrides":{}}""")
            ),
        )
        // args 成员类型不符（对象当字符串）也不能崩
        assertEquals("点击", StepAdapter.stepSummary(stepFrom("""{"order":6,"action":"click","args":{"text":{}}}""")))
    }

    @Test fun reportWithNullUploadResponseAndNullCasesParses() {
        val report = File.createTempFile("report", ".json").apply {
            writeText(
                """{"success":true,"cases":[{"steps":[
                   {"order":1,"action":"click","status":"PASS","duration":0.1,"error":null},
                   {"order":2,"action":"input","status":"FAIL","duration":0.2,"error":{"message":"x"}}
                   ]}],"upload_response":null}"""
            )
            deleteOnExit()
        }
        val result = RunBridge.parseReport(report)
        assertTrue(result.success)
        assertEquals(2, result.steps.size)
        assertNull(result.steps[0].error)
        // error 是对象而非字符串时不当成错误文本，也不能崩
        assertNull(result.steps[1].error)
        assertFalse(result.uploadOk)
        assertNull(result.executionId)
    }

    @Test fun reportWithNullCasesAndUploadObjectParses() {
        val report = File.createTempFile("report", ".json").apply {
            writeText(
                """{"success":false,"cases":null,
                   "upload_response":{"execution_id":7,"report_id":null}}"""
            )
            deleteOnExit()
        }
        val result = RunBridge.parseReport(report)
        assertFalse(result.success)
        assertTrue(result.steps.isEmpty())
        assertTrue(result.uploadOk)
        assertEquals(7, result.executionId)
        assertNull(result.reportId)
    }

    @Test fun paginatedEnvelopeNullsStaySafe() {
        // 钉住 Gson 行为：显式 JSON null 会绕过 Kotlin 默认值把字段置 null，
        // 因此 items/name 等必须声明可空、由消费处兜底
        val page = gson.fromJson("""{"total":2,"items":null}""", PaginatedCases::class.java)
        assertNull(page.items)
        assertTrue((page.items ?: emptyList()).isEmpty())

        val item = gson.fromJson("""{"id":1,"name":null}""", CaseItem::class.java)
        assertNull(item.name)
        assertEquals("未命名", item.name ?: "未命名")

        val bundle = gson.fromJson("""{"case_files":null,"assets":null}""", ScriptBundle::class.java)
        assertNull(bundle.case_files)
        assertNull(bundle.assets)
    }
}
