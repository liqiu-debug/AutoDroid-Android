package org.autojs.autojs.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Offline JNI and bundled OCR tests. No screenshots, accessibility, gestures,
 * app launches, permissions or server traffic are involved. */
class AdaptiveImageMatcherAcceptanceTest {
    private fun glyph(width:Int=40,height:Int=40):Bitmap {
        val pixels=IntArray(width*height) {i ->
            val x=i%width;val y=i/width
            when {
                x in 4 until width-4 && y in 4 until height-4 && (abs(x-width/3)<3 || abs(y-height/2)<3 || abs(x+y-width)<2) -> Color.rgb(210,35,45)
                else -> Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }
    private fun paste(screen:Bitmap,image:Bitmap,x:Int,y:Int) {Canvas(screen).drawBitmap(image,x.toFloat(),y.toFloat(),null)}
    private fun screen(width:Int=240,height:Int=220)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
    private fun config(template:Bitmap,descriptor:JSONObject?=null):JSONObject {
        val options=JSONObject().put("timeout_ms",30000).put("template_sha256","0".repeat(64))
        val match=JSONObject().put("profile","adaptive-v2").put("template_sha256","0".repeat(64))
        if(descriptor!=null) {
            val canonical=descriptor.toString()
            match.put("descriptor",descriptor).put("descriptor_canonical",canonical).put("descriptor_sha256",AdaptiveImageMatcher.sha256(canonical))
        }
        return options.put("image_match",match)
    }
    @Test fun scaleBetweenOldDiscreteStepsLocatesInOriginalPixels() {
        assertTrue(OpenCVLoader.initDebug())
        val template=glyph();val shot=screen()
        val pixels=IntArray(template.width*template.height).also {template.getPixels(it,0,template.width,0,0,template.width,template.height)}
        val resized=AdaptiveImageMatcher.resize(AdaptiveImageMatcher.Pixels(40,40,pixels),47,47).bitmap()
        paste(shot,resized,110,90)
        try {
            val result=AdaptiveImageMatcher.match(shot,template,config(template))
            assertEquals(result.toString(),"matched",result.getString("status"))
            val rect=result.getJSONArray("rect")
            assertEquals(110,rect.getInt(0));assertEquals(90,rect.getInt(1));assertEquals(47,rect.getInt(2));assertEquals(47,rect.getInt(3))
        } finally {shot.recycle();template.recycle();resized.recycle()}
    }
    @Test fun duplicateVerifiedTargetsNeverAuthorizeAClick() {
        assertTrue(OpenCVLoader.initDebug())
        val template=glyph();val shot=screen();paste(shot,template,30,40);paste(shot,template,150,140)
        try {val result=AdaptiveImageMatcher.match(shot,template,config(template));assertEquals(result.toString(),"ambiguous",result.getString("status"))}
        finally {shot.recycle();template.recycle()}
    }
    @Test fun canonicalTamperAndUnknownVersionsAreRejected() {
        assertTrue(OpenCVLoader.initDebug())
        val template=glyph();val shot=screen()
        try {
            val opts=config(template)
            opts.getJSONObject("image_match").put("descriptor_canonical","{\"schema_version\":1}").put("descriptor_sha256","0".repeat(64))
            assertEquals("descriptor_hash_mismatch",AdaptiveImageMatcher.match(shot,template,opts).getString("reason"))
            opts.getJSONObject("image_match").put("profile","adaptive-v3")
            assertEquals("unsupported",AdaptiveImageMatcher.match(shot,template,opts).getString("status"))
            opts.getJSONObject("image_match").put("profile","adaptive-v2")
            val canonical="{\"schema_version\":2}"
            opts.getJSONObject("image_match").put("descriptor_canonical",canonical).put("descriptor_sha256",AdaptiveImageMatcher.sha256(canonical))
            assertEquals("unsupported",AdaptiveImageMatcher.match(shot,template,opts).getString("status"))
        } finally {shot.recycle();template.recycle()}
    }
    @Test fun aVerifiedWholeTemplateDoesNotNeedAnotherOcrPass() {
        assertTrue(OpenCVLoader.initDebug())
        val template=glyph()
        val descriptor=JSONObject().put("schema_version",1).put("template_size",JSONArray(intArrayOf(40,40)))
            .put("regions",JSONArray().put(JSONObject().put("id","whole").put("kind","visual").put("required",true).put("rect",JSONArray(doubleArrayOf(0.0,0.0,1.0,1.0)))))
            .put("relations",JSONArray()).put("tap",JSONObject().put("region_id","whole").put("anchor",JSONArray(doubleArrayOf(.5,.5))))
        try {val result=AdaptiveImageMatcher.match(template,template,config(template,descriptor));assertEquals(result.toString(),"matched",result.getString("status"))}
        finally {template.recycle()}
    }
    @Test fun twoCorrectLabelCandidatesPreserveExistenceButRejectClickSelection() {
        val icon=AdaptiveImageMatcher.Box(100,100,40,40)
        val one=AdaptiveImageMatcher.Text("云闪付",.99,AdaptiveImageMatcher.Box(150,101,80,18))
        val two=AdaptiveImageMatcher.Text("云闪付",.99,AdaptiveImageMatcher.Box(150,123,80,18))
        assertEquals("present",AdaptiveImageMatcher.textEvidence(listOf(one,two),"云闪付"))
        assertEquals(2,AdaptiveImageMatcher.textAssignments(mapOf("label" to listOf(one,two))).size)
        assertFalse(AdaptiveImageMatcher.sameRegionTargets(mapOf("visual" to icon,"label" to one.box),mapOf("visual" to icon,"label" to two.box)))
    }
    @Test fun deadlineAndThreadInterruptionAreUnknownRatherThanAbsence() {
        assertTrue(OpenCVLoader.initDebug())
        val template=glyph();val shot=screen()
        try {
            val opts=config(template).put("deadline_ms",System.currentTimeMillis()-1)
            assertEquals("unstable",AdaptiveImageMatcher.match(shot,template,opts).getString("status"))
            Thread.currentThread().interrupt()
            assertEquals("unstable",AdaptiveImageMatcher.match(shot,template,config(template)).getString("status"))
        } finally {Thread.interrupted();shot.recycle();template.recycle()}
    }
    @Test fun offlineSharedAdaptiveFixtures() {
        val directory=File(Environment.getExternalStorageDirectory(),"autodroid-adaptive-acceptance")
        val manifestFile=File(directory,"manifest.json")
        assumeTrue("Adaptive PNG corpus was not supplied",manifestFile.isFile)
        assertTrue(OpenCVLoader.initDebug())
        val records=JSONArray();val manifest=JSONObject(manifestFile.readText()).getJSONArray("cases")
        for(index in 0 until manifest.length()) {
            val item=manifest.getJSONObject(index)
            val template=BitmapFactory.decodeFile(File(directory,item.getString("template")).path)
            val shot=BitmapFactory.decodeFile(File(directory,item.getString("screen")).path)
            assertNotNull(template);assertNotNull(shot)
            val opts=item.optJSONObject("options") ?: config(template)
            val digest=MessageDigest.getInstance("SHA-256").digest(File(directory,item.getString("template")).readBytes()).joinToString("") {"%02x".format(it.toInt() and 255)}
            opts.put("template_sha256",digest).put("timeout_ms",60000)
            opts.getJSONObject("image_match").put("template_sha256",digest)
            val start=System.nanoTime()
            val result=try {AdaptiveImageMatcher.match(shot,template,opts)}finally {shot.recycle();template.recycle()}
            result.put("id",item.getString("id")).put("elapsed_ms",(System.nanoTime()-start)/1e6)
            records.put(result)
            File(directory,"android-results.json").writeText(records.toString(2))
            assertEquals(result.toString(),item.getString("expected_status"),result.getString("status"))
        }
    }
}
