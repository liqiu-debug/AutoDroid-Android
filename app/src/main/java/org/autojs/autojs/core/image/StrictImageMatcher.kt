package org.autojs.autojs.core.image

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Strict image clicks never use ScreenMetrics: all results remain screenshot pixels.
 * NCC proposes locations; independent foreground, contour and spatial colour checks
 * decide whether a location is safe. More than one viable object rejects the click.
 */
object StrictImageMatcher {
    private val scales = doubleArrayOf(1.0, .95, 1.05, .9, 1.1, .85, 1.15, .8, 1.2)
    private class Expired : RuntimeException()
    private data class Raster(val width: Int, val height: Int, val rgb: IntArray) {
        val gray = DoubleArray(rgb.size) { i ->
            val c = rgb[i]; .299 * (c shr 16 and 255) + .587 * (c shr 8 and 255) + .114 * (c and 255)
        }
        fun grayMat(): Mat = Mat(height, width, CvType.CV_8UC1).also { mat ->
            mat.put(0, 0, ByteArray(gray.size) { gray[it].roundToInt().coerceIn(0, 255).toByte() })
        }
    }
    private data class Features(val edge: BooleanArray, val foreground: BooleanArray, val count: Int, val std: Double)
    private data class Proposal(val x: Int, val y: Int, val w: Int, val h: Int, val score: Double, val template: Raster)
    internal data class Verification(val ncc: Double, val recall: Double, val precision: Double, val foregroundError: Double,
                                     val tileError: Double, val colorError: Double?, val accepted: Boolean, val localError: Double = 0.0) {
        fun json(): JSONObject = JSONObject().put("ncc",ncc).put("edge_recall",recall).put("edge_precision",precision)
            .put("edge_coverage",min(recall,precision)).put("foreground_error",foregroundError).put("tile_error",tileError)
            .put("local_error",localError).put("color_active",colorError!=null).put("color_error",colorError ?: JSONObject.NULL)
            .put("accepted",accepted).put("reason",if(accepted)"accepted" else "verification_failed")
    }
    /** Pure numerical entry point for JVM regression tests; no Bitmap/JNI calls. */
    internal fun verifyArgb(width: Int, height: Int, template: IntArray, candidate: IntArray, ncc: Double): Verification {
        require(width>=8 && height>=8 && template.size==width*height && candidate.size==template.size)
        return metrics(Raster(width,height,template),Raster(width,height,candidate),ncc)
    }
    private fun array(values: Iterable<*>): JSONArray = JSONArray(values.toList())
    private fun numbers(json: JSONObject, name: String, size: Int): DoubleArray? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.optJSONArray(name) ?: throw IllegalArgumentException("invalid_$name")
        require(value.length() == size) { "invalid_$name" }
        return DoubleArray(size) { value.optDouble(it, Double.NaN).also { n -> require(n.isFinite()) { "invalid_$name" } } }
    }
    private fun raster(bitmap: Bitmap): Raster {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Raster(bitmap.width, bitmap.height, pixels)
    }
    private fun features(r: Raster): Features {
        val w = r.width; val h = r.height; val mag = DoubleArray(w * h)
        var sum = 0.0; var sum2 = 0.0
        for (v in r.gray) { sum += v; sum2 += v * v }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x; val g = r.gray
            val gx = -g[i-w-1] + g[i-w+1] - 2*g[i-1] + 2*g[i+1] - g[i+w-1] + g[i+w+1]
            val gy = -g[i-w-1] - 2*g[i-w] - g[i-w+1] + g[i+w-1] + 2*g[i+w] + g[i+w+1]
            mag[i] = hypot(gx, gy)
        }
        val sorted = mag.sortedArray(); val threshold = max(8.0, sorted[((sorted.size - 1) * .9).toInt()] * .25)
        val edge = BooleanArray(w*h) { mag[it] >= threshold }; val foreground = BooleanArray(w*h)
        for (y in 0 until h) for (x in 0 until w) if (edge[y*w+x]) {
            for (dy in -1..1) for (dx in -1..1) if (x+dx in 0 until w && y+dy in 0 until h) foreground[(y+dy)*w+x+dx] = true
        }
        return Features(edge, foreground, edge.count { it }, sqrt(max(0.0, sum2/r.gray.size - (sum/r.gray.size).pow(2))))
    }
    private fun coverage(a: BooleanArray, b: BooleanArray, w: Int, h: Int): Double {
        var total = 0; var found = 0
        for (y in 0 until h) for (x in 0 until w) if (a[y*w+x]) {
            total++; var near = false
            for (dy in -1..1) for (dx in -1..1) if (x+dx in 0 until w && y+dy in 0 until h && b[(y+dy)*w+x+dx]) near = true
            if (near) found++
        }
        return if (total == 0) 0.0 else found.toDouble()/total
    }
    private fun crop(r: Raster, x: Int, y: Int, w: Int, h: Int): Raster = Raster(w, h, IntArray(w*h) { r.rgb[(y+it/w)*r.width+x+it%w] })
    private fun metrics(t: Raster, c: Raster, score: Double): Verification {
        val a = features(t); val b = features(c); val tiles = DoubleArray(16); val counts = IntArray(16)
        var grayError = 0.0; var colorError = 0.0; var count = 0; var saturatedT = 0; var saturatedC = 0
        for (i in t.rgb.indices) {
            val tr = t.rgb[i] shr 16 and 255; val tg = t.rgb[i] shr 8 and 255; val tb = t.rgb[i] and 255
            val cr = c.rgb[i] shr 16 and 255; val cg = c.rgb[i] shr 8 and 255; val cb = c.rgb[i] and 255
            if (max(tr,max(tg,tb))-min(tr,min(tg,tb)) >= 32) saturatedT++
            if (max(cr,max(cg,cb))-min(cr,min(cg,cb)) >= 32) saturatedC++
            if (a.foreground[i] || b.foreground[i]) {
                val e = abs(t.gray[i]-c.gray[i])/255.0
                grayError += e; colorError += (abs(tr-cr)+abs(tg-cg)+abs(tb-cb))/(3.0*255); count++
                val tile = min(3,(i/t.width)*4/t.height)*4+min(3,(i%t.width)*4/t.width)
                tiles[tile] += e; counts[tile]++
            }
        }
        val maxTile = (0..15).filter { counts[it] >= 8 }.maxOfOrNull { tiles[it]/counts[it] } ?: 0.0
        // Fixed overlapping windows keep one changed character from being
        // diluted by the shared button border or the rest of a long label.
        var localError = 0.0
        for (y in 0 until t.height step 8) for (x in 0 until t.width step 8) {
            var localSum = 0.0; var localCount = 0
            for (yy in y until min(y+16,t.height)) for (xx in x until min(x+16,t.width)) {
                val i = yy*t.width+xx
                if (a.foreground[i] || b.foreground[i]) { localSum += abs(t.gray[i]-c.gray[i])/255.0; localCount++ }
            }
            if (localCount >= 8) localError = max(localError,localSum/localCount)
        }
        val colorActive = max(saturatedT,saturatedC).toDouble()/t.rgb.size >= .1
        val recall=coverage(a.edge,b.edge,t.width,t.height); val precision=coverage(b.edge,a.edge,t.width,t.height)
        val error=if(count>0)grayError/count else 1.0; val worst=max(maxTile,error)
        val color=if(colorActive) { if(count>0)colorError/count else 1.0 } else null
        val accepted=score>=.92 && a.count>0 && b.count>0 && b.std>.5 && min(recall,precision)>=.88 && error<=.12 && max(worst,localError)<=.22 && (color==null || color<=.08)
        return Verification(score,recall,precision,error,worst,color,accepted,localError)
    }
    private fun same(a: Proposal,b: Proposal): Boolean {
        val intersection = max(0,min(a.x+a.w,b.x+b.w)-max(a.x,b.x))*max(0,min(a.y+a.h,b.y+b.h)-max(a.y,b.y))
        val iou = intersection.toDouble()/(a.w*a.h+b.w*b.h-intersection)
        val distance = hypot(a.x+a.w/2.0-b.x-b.w/2.0,a.y+a.h/2.0-b.y-b.h/2.0)
        return iou > .35 || distance <= .25*min(min(a.w,a.h),min(b.w,b.h))
    }
    @JvmStatic fun match(shot: Bitmap, template: Bitmap, options: JSONObject): JSONObject {
        val started = System.nanoTime(); val wallNow = System.currentTimeMillis().toDouble()
        val timeout = options.optDouble("timeout_ms",5000.0).coerceIn(1.0,15000.0)
        val wallBudget = options.optDouble("deadline_ms",wallNow+timeout)-wallNow
        val budget = min(timeout,wallBudget)
        val deadline = started + (budget*1e6).toLong()
        fun check() { if (!budget.isFinite() || System.nanoTime() >= deadline) throw Expired() }
        val candidates = JSONArray(); val quality = JSONObject();var searchMS=0.0;var verificationMS=0.0
        fun result(status: String,reason: String,point: JSONArray? = null): JSONObject = JSONObject()
            .put("status",status).put("reason",reason).put("matcher_version","strict-v1")
            .put("candidates",candidates).put("point",point ?: JSONObject.NULL).put("quality",quality)
            .put("timings",JSONObject().put("total_ms",(System.nanoTime()-started)/1e6).put("search_ms",searchMS).put("verification_ms",verificationMS))
        try {
            check()
            val anchor = numbers(options,"tap_anchor",2) ?: doubleArrayOf(.5,.5)
            require(anchor.all { it in 0.0..1.0 }) { "invalid_tap_anchor" }
            val sourceSize = numbers(options,"source_size",2)
            var base = 1.0
            if(sourceSize != null) {
                require(sourceSize.all { it > 0 }) { "invalid_source_size" }
                val rx = shot.width/sourceSize[0]; val ry = shot.height/sourceSize[1]
                if(abs(rx/ry-1) > .03) return result("unsupported","aspect_ratio_changed")
                base = (rx+ry)/2
            }
            var roi = Rect(0,0,shot.width,shot.height)
            numbers(options,"search_region",4)?.let { r ->
                require(r.all { it in 0.0..1.0 } && r[2] > r[0] && r[3] > r[1]) { "invalid_search_region" }
                val x = floor(r[0]*shot.width).toInt(); val y = floor(r[1]*shot.height).toInt()
                roi = Rect(x,y,min(shot.width,ceil(r[2]*shot.width).toInt())-x,min(shot.height,ceil(r[3]*shot.height).toInt())-y)
            }
            val original = raster(template); val f = features(original)
            val invalidReason=if(min(original.width,original.height)<8) "template_too_small" else if(f.std<=.5) "constant_template" else if(f.count==0) "no_foreground" else "ok"
            quality.put("gray_std",f.std).put("edge_count",f.count).put("edge_density",f.count.toDouble()/original.rgb.size)
                .put("size",JSONArray(intArrayOf(original.width,original.height))).put("valid",invalidReason=="ok").put("reason",invalidReason)
                .put("warnings",JSONArray().apply { if(f.std<10)put("low_contrast");if(f.count.toDouble()/original.rgb.size<.02)put("sparse_features");if(f.count.toDouble()/original.rgb.size>.6)put("dense_texture") })
                .put("colorful",original.rgb.count { c -> max(c shr 16 and 255,max(c shr 8 and 255,c and 255))-min(c shr 16 and 255,min(c shr 8 and 255,c and 255))>=32 }.toDouble()/original.rgb.size>=.1)
            if(invalidReason!="ok") return result("invalid_template",invalidReason)
            val screen = raster(shot); val search = crop(screen,roi.x,roi.y,roi.width,roi.height)
            val screenMat = search.grayMat(); val proposed = mutableListOf<Proposal>(); var overflow = false
            val searchStarted=System.nanoTime()
            try {
                val sizes=mutableSetOf<Pair<Int,Int>>()
                for(scale in scales) {
                    check(); val w = (original.width*base*scale).roundToInt(); val h = (original.height*base*scale).roundToInt()
                    if(min(w,h)<8 || w>roi.width || h>roi.height || !sizes.add(w to h)) continue
                    val bitmap = if(w==template.width && h==template.height) template else Bitmap.createScaledBitmap(template,w,h,true)
                    val t = try { raster(bitmap) } finally { if(bitmap!==template) bitmap.recycle() }
                    val tm = t.grayMat(); val scores = Mat()
                    try {
                        // Coarse proposals use explicitly named CCOEFF_NORMED. The score map is
                        // full resolution, so every proposal is already refined in original pixels.
                        Imgproc.matchTemplate(screenMat,tm,scores,Imgproc.TM_CCOEFF_NORMED)
                        for(index in 0..20) {
                            check(); val peak = Core.minMaxLoc(scores)
                            if(!peak.maxVal.isFinite() || peak.maxVal<.82) break
                            if(index==20) { overflow=true; break }
                            val x=peak.maxLoc.x.toInt();val y=peak.maxLoc.y.toInt()
                            proposed.add(Proposal(x+roi.x,y+roi.y,w,h,peak.maxVal,t))
                            val x0=max(0,x-max(1,w/3));val y0=max(0,y-max(1,h/3))
                            val x1=min(scores.cols(),x+max(1,w/3)+1);val y1=min(scores.rows(),y+max(1,h/3)+1)
                            val suppressed=scores.submat(Rect(x0,y0,x1-x0,y1-y0));try { suppressed.setTo(Scalar(-2.0)) } finally { suppressed.release() }
                        }
                    } finally { tm.release();scores.release() }
                }
            } finally { screenMat.release() }
            searchMS=(System.nanoTime()-searchStarted)/1e6
            val verificationStarted=System.nanoTime();val verified = mutableListOf<Pair<Proposal,JSONObject>>()
            // Verify before suppression: a slightly lower-scoring scale can pass structural checks.
            for(p in proposed.sortedByDescending { it.score }) {
                check(); val verification=metrics(p.template,crop(screen,p.x,p.y,p.w,p.h),p.score)
                val m=verification.json(); val accepted=verification.accepted
                val c=JSONObject().put("rect",JSONArray(doubleArrayOf(p.x.toDouble(),p.y.toDouble(),p.w.toDouble(),p.h.toDouble())))
                    .put("point",JSONArray(doubleArrayOf(min(shot.width-1.0,p.x+min(p.w-1.0,anchor[0]*p.w)),min(shot.height-1.0,p.y+min(p.h-1.0,anchor[1]*p.h)))))
                    .put("similarity",p.score).put("metrics",m).put("accepted",accepted)
                verified.add(p to c)
            }
            val objects=mutableListOf<Pair<Proposal,JSONObject>>()
            for(v in verified.sortedWith(compareByDescending<Pair<Proposal,JSONObject>> { it.second.getBoolean("accepted") }.thenByDescending { it.first.score })) {
                if(objects.none { same(it.first,v.first) }) objects.add(v)
            }
            verificationMS=(System.nanoTime()-verificationStarted)/1e6
            if(objects.size>64)overflow=true
            objects.take(64).forEach { candidates.put(it.second) }
            if(overflow) return result("ambiguous","candidate_overflow")
            val accepted=objects.filter { it.second.getBoolean("accepted") }
            if(accepted.size>1) return result("ambiguous","multiple_verified_targets")
            if(accepted.isEmpty()) return result("not_found","no_verified_candidate")
            val best=accepted.first()
            val rival=objects.any { it!==best && it.first.score>=.90 && best.first.score-it.first.score<.03 &&
                min(it.second.getJSONObject("metrics").getDouble("edge_recall"),it.second.getJSONObject("metrics").getDouble("edge_precision"))>=.80 &&
                it.second.getJSONObject("metrics").getDouble("foreground_error")<=.16 }
            if(rival) return result("ambiguous","insufficient_margin")
            check(); return result("matched","unique_verified_target",best.second.getJSONArray("point")).put("rect",best.second.getJSONArray("rect"))
        } catch(_: Expired) { return result("unstable","deadline_exceeded")
        } catch(e: IllegalArgumentException) { return result("invalid_template",e.message ?: "invalid_options")
        } catch(e: Exception) { return result("unsupported","native_match_error:${e.javaClass.simpleName}") }
    }
}
