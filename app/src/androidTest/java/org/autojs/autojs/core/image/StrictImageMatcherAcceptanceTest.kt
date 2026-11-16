package org.autojs.autojs.core.image

import android.graphics.BitmapFactory
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File
import kotlin.math.*

/** Offline production JNI acceptance on a dedicated emulator. No gestures,
 * accessibility service, screenshots or network requests are performed here.
 */
class StrictImageMatcherAcceptanceTest {
    @Test fun sharedPngCorpus() {
        val directory=File(Environment.getExternalStorageDirectory(),"autodroid-image-acceptance")
        assumeTrue("Shared acceptance corpus was not supplied",File(directory,"manifest.json").isFile)
        assertTrue("OpenCV native library must load",OpenCVLoader.initDebug())
        val manifest=JSONObject(File(directory,"manifest.json").readText())
        val cases=manifest.getJSONArray("cases");assertTrue(cases.length()>0)
        val records=JSONArray();val times=mutableListOf<Double>()
        var positives=0;var negatives=0;var tp=0;var fp=0;var wrong=0;var loadErrors=0
        for(index in 0 until cases.length()) {
            val item=cases.getJSONObject(index);val positive=item.getString("label")=="positive"
            if(positive)positives++ else negatives++
            val screen=BitmapFactory.decodeFile(File(directory,item.getString("screen")).path)
            val template=BitmapFactory.decodeFile(File(directory,item.getString("template")).path)
            val started=System.nanoTime()
            val result=try {
                if(screen==null||template==null) { loadErrors++;JSONObject().put("status","invalid_fixture").put("reason","image_load_failed") }
                else StrictImageMatcher.match(screen,template,item.optJSONObject("options")?:JSONObject())
            } finally { screen?.recycle();template?.recycle() }
            val elapsed=(System.nanoTime()-started)/1e6;times.add(elapsed)
            val matched=result.getString("status")=="matched";var correct=false;var iou=0.0
            val expected=item.optJSONArray("expected_rect");val actual=result.optJSONArray("rect");val point=result.optJSONArray("point")
            if(positive&&matched&&expected!=null&&actual!=null&&point!=null) {
                val ex=expected.getDouble(0);val ey=expected.getDouble(1);val ew=expected.getDouble(2);val eh=expected.getDouble(3)
                val ax=actual.getDouble(0);val ay=actual.getDouble(1);val aw=actual.getDouble(2);val ah=actual.getDouble(3)
                val intersection=max(0.0,min(ex+ew,ax+aw)-max(ex,ax))*max(0.0,min(ey+eh,ay+ah)-max(ey,ay))
                iou=intersection/max(1.0,ew*eh+aw*ah-intersection)
                val px=point.getDouble(0);val py=point.getDouble(1)
                correct=iou>=item.optDouble("min_iou",.8)&&px>=ex&&px<ex+ew&&py>=ey&&py<ey+eh
            }
            if(positive&&correct)tp++
            if(matched&&!positive)fp++
            if(matched&&positive&&!correct)wrong++
            if(!positive)correct=!matched&&result.getString("status")!="invalid_fixture"
            result.put("id",item.opt("id")).put("label",if(positive)"positive" else "negative")
                .put("correct",correct).put("iou",iou).put("elapsed_ms",elapsed)
            records.put(result)
        }
        times.sort()
        fun percentile(p: Double): Double { val at=(times.size-1)*p;val lo=floor(at).toInt();val hi=ceil(at).toInt();return times[lo]+(times[hi]-times[lo])*(at-lo) }
        val summary=JSONObject().put("matcher_version","strict-v1").put("engine","android_native_jni").put("capability_validated",false)
            .put("total",cases.length()).put("positives",positives).put("negatives",negatives).put("true_positives",tp)
            .put("false_positives",fp).put("wrong_geometry",wrong).put("fixture_load_errors",loadErrors)
            .put("recall",if(positives>0)tp.toDouble()/positives else 0.0).put("p50_ms",percentile(.5)).put("p95_ms",percentile(.95)).put("cases",records)
        File(directory,"android-results.json").writeText(summary.toString(2))
        println("Strict shared acceptance: TP=$tp/$positives FP=$fp/$negatives wrong_geometry=$wrong p50=${percentile(.5)}ms p95=${percentile(.95)}ms")
        assertEquals(0,loadErrors);assertEquals(0,fp);assertEquals(0,wrong)
    }
}
