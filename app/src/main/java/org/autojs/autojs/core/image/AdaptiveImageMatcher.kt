package org.autojs.autojs.core.image

import android.graphics.Bitmap
import org.autojs.autojs.runtime.api.OcrMLKit
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.TimeoutException
import kotlin.math.*

/** One immutable template descriptor, shared by all recording/execution platforms.
 * Coarse NCC only proposes candidates. Original-resolution independent checks,
 * exact adjacent text, all required regions, and final ambiguity govern acceptance.
 */
object AdaptiveImageMatcher {
    const val VERSION = "adaptive-v2"
    private val ocr by lazy { OcrMLKit() }
    private class Expired : RuntimeException()
    internal data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
        val cx get() = x + w / 2.0
        val cy get() = y + h / 2.0
        fun json() = JSONArray(intArrayOf(x, y, w, h))
    }
    internal data class Pixels(val width: Int, val height: Int, val argb: IntArray) {
        init { require(width > 0 && height > 0 && argb.size == width * height) }
        fun crop(b: Box) = Pixels(b.w, b.h, IntArray(b.w*b.h) { argb[(b.y+it/b.w)*width+b.x+it%b.w] })
        fun bitmap(): Bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        fun grayMat(): Mat = Mat(height, width, CvType.CV_8UC1).also { mat ->
            mat.put(0, 0, ByteArray(argb.size) { i ->
                val c=argb[i]
                floor(.299*(c shr 16 and 255)+.587*(c shr 8 and 255)+.114*(c and 255)+.5).toInt().coerceIn(0,255).toByte()
            })
        }
    }
    internal data class Text(val text: String, val score: Double, val box: Box)
    private data class Region(val id: String, val kind: String, val required: Boolean, val box: Box, val text: String?, val coverage:Box=box)
    private data class Relation(val visual: String, val text: String, val direction: String, val gap: Double, val alignment: Double)
    private data class Descriptor(val regions: List<Region>, val relations: List<Relation>, val tapRegion: String, val anchor: DoubleArray)
    private data class Proposal(val box: Box, val score: Double, val scale: Double)
    private data class Visual(val box: Box, val score: Double, val metrics: JSONObject, val accepted:Boolean=true)
    private data class Search(val candidates: List<Visual>, val incomplete: Boolean)
    private data class Combination(val matches: Map<String, JSONObject>, val boxes: Map<String, Box>, val score: Double, val point: DoubleArray) {
        val box get() = boxes.values.reduce { a,b -> union(a,b) }
    }

    internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun normalizeText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).trim().replace(Regex("(?U)\\s+"), " ")
    internal fun textEvidence(items:List<Text>, expected:String):String {
        if(items.any {it.text.isNotBlank() && (!it.score.isFinite() || it.box.w<=0 || it.box.h<=0)})return "invalid_ocr_contract"
        if(items.isEmpty() || items.any {it.text.isNotBlank() && it.score<.90})return "ocr_low_confidence"
        return if(items.any {normalizeText(it.text)==normalizeText(expected)})"present" else "absent"
    }
    internal fun textAssignments(matches:Map<String,List<Text>>):List<Map<String,Text>> {
        var output=listOf(emptyMap<String,Text>())
        for((id,items) in matches) {
            val next=mutableListOf<Map<String,Text>>()
            for(assignment in output)for(item in items) {
                if(assignment.values.any {iou(it.box,item.box)>=.9})continue
                next.add(assignment+(id to item))
                require(next.size<=64) {"candidate_overflow"}
            }
            output=next
        }
        return output
    }
    internal fun sameRegionTargets(a:Map<String,Box>,b:Map<String,Box>):Boolean = a.keys==b.keys && a.all { (id,rect) ->
        b[id]?.let {other -> iou(rect,other)>=.9 && hypot(rect.cx-other.cx,rect.cy-other.cy)<=max(2.0,.02*min(rect.w,rect.h))} ?: false
    }
    internal fun contained(region:Box,scope:Box):Boolean = region.x>=scope.x && region.y>=scope.y && region.right<=scope.right && region.bottom<=scope.bottom
    private fun intersection(a:Box,b:Box):Box? {
        val x=max(a.x,b.x);val y=max(a.y,b.y);val right=min(a.right,b.right);val bottom=min(a.bottom,b.bottom)
        return if(right>x && bottom>y)Box(x,y,right-x,bottom-y) else null
    }

    /** Shared half-pixel, bilinear RGB/alpha interpolation with explicit half-up rounding. */
    internal fun resize(input: Pixels, width: Int, height: Int): Pixels {
        require(width > 0 && height > 0)
        if (width == input.width && height == input.height) return input
        val x0=IntArray(width);val x1=IntArray(width);val fraction=DoubleArray(width)
        for(x in 0 until width) {
            val sx=((x+.5)*input.width/width-.5).coerceIn(0.0,input.width-1.0)
            x0[x]=floor(sx).toInt();x1[x]=min(input.width-1,x0[x]+1);fraction[x]=sx-x0[x]
        }
        val output=IntArray(width*height)
        for(y in 0 until height) {
            val sy=((y+.5)*input.height/height-.5).coerceIn(0.0,input.height-1.0)
            val y0=floor(sy).toInt();val y1=min(input.height-1,y0+1);val fy=sy-y0
            for(x in 0 until width) {
                val fx=fraction[x];val a=input.argb[y0*input.width+x0[x]];val b=input.argb[y0*input.width+x1[x]]
                val c=input.argb[y1*input.width+x0[x]];val d=input.argb[y1*input.width+x1[x]]
                var color=0
                for(channel in 0..3) {
                    val shift=24-channel*8
                    val upper=(a ushr shift and 255)*(1-fx)+(b ushr shift and 255)*fx
                    val lower=(c ushr shift and 255)*(1-fx)+(d ushr shift and 255)*fx
                    color=color or (floor(upper*(1-fy)+lower*fy+.5).toInt().coerceIn(0,255) shl shift)
                }
                output[y*width+x]=color
            }
        }
        return Pixels(width,height,output)
    }
    internal fun relationPass(visual: Box, text: Box, direction: String, maxGap: Double, maxAlignment: Double): Boolean {
        val unit=min(visual.w,visual.h).toDouble()
        val gap:Double; val alignment:Double
        when(direction) {
            "right" -> {gap=(text.x-visual.right)/unit;alignment=abs(text.cy-visual.cy)/max(visual.h,text.h)}
            "left" -> {gap=(visual.x-text.right)/unit;alignment=abs(text.cy-visual.cy)/max(visual.h,text.h)}
            "below" -> {gap=(text.y-visual.bottom)/unit;alignment=abs(text.cx-visual.cx)/max(visual.w,text.w)}
            "above" -> {gap=(visual.y-text.bottom)/unit;alignment=abs(text.cx-visual.cx)/max(visual.w,text.w)}
            else -> return false
        }
        return gap >= -.05 && gap <= maxGap && alignment <= maxAlignment
    }
    private fun union(a: Box,b: Box) = Box(min(a.x,b.x),min(a.y,b.y),max(a.right,b.right)-min(a.x,b.x),max(a.bottom,b.bottom)-min(a.y,b.y))
    private fun iou(a: Box,b: Box): Double {
        val area=max(0,min(a.right,b.right)-max(a.x,b.x)).toDouble()*max(0,min(a.bottom,b.bottom)-max(a.y,b.y))
        return area/max(1.0,a.w.toDouble()*a.h+b.w.toDouble()*b.h-area)
    }
    private fun same(a: Box,b: Box) = iou(a,b)>.35 || hypot(a.cx-b.cx,a.cy-b.cy)<=.25*min(min(a.w,a.h),min(b.w,b.h))
    private fun raster(bitmap: Bitmap): Pixels = Pixels(bitmap.width,bitmap.height,IntArray(bitmap.width*bitmap.height).also {
        bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    })
    private fun equivalent(a: Any?,b: Any?): Boolean {
        if(a is JSONObject && b is JSONObject) {
            val keys=a.keys().asSequence().toSet()
            return keys==b.keys().asSequence().toSet() && keys.all { equivalent(a.get(it),b.get(it)) }
        }
        if(a is JSONArray && b is JSONArray) return a.length()==b.length() && (0 until a.length()).all { equivalent(a.get(it),b.get(it)) }
        if(a is Number && b is Number) return a.toDouble()==b.toDouble()
        return a==b
    }
    private fun numbers(obj:JSONObject,name:String,count:Int):DoubleArray {
        val list=obj.optJSONArray(name) ?: throw IllegalArgumentException("invalid_$name")
        require(list.length()==count) { "invalid_$name" }
        return DoubleArray(count) { require(list.opt(it) is Number) {"invalid_$name"};list.getDouble(it).also { value -> require(value.isFinite()) { "invalid_$name" } } }
    }
    private fun descriptor(config: JSONObject?, template: Pixels): Descriptor {
        val canonical=config?.optString("descriptor_canonical", "").orEmpty()
        if(canonical.isEmpty()) {
            require(config==null || (!config.has("descriptor") && !config.has("descriptor_sha256"))) { "missing_descriptor_canonical" }
            return Descriptor(listOf(Region("full","visual",true,Box(0,0,template.width,template.height),null)),emptyList(),"full",doubleArrayOf(.5,.5))
        }
        require(sha256(canonical)==config!!.optString("descriptor_sha256").lowercase()) { "descriptor_hash_mismatch" }
        val data=JSONObject(canonical)
        if(config.has("descriptor"))require(config.opt("descriptor") is JSONObject && equivalent(config.getJSONObject("descriptor"),data)) {"descriptor_mismatch"}
        require(data.opt("schema_version") is Number && data.getDouble("schema_version")==1.0) { "unsupported_descriptor_version" }
        val size=numbers(data,"template_size",2)
        require(size[0]==template.width.toDouble() && size[1]==template.height.toDouble()) { "descriptor_template_size_mismatch" }
        val raw=data.optJSONArray("regions") ?: throw IllegalArgumentException("invalid_regions")
        require(raw.length() in 1..16) { "invalid_regions" }
        val regions=(0 until raw.length()).map { i ->
            val item=raw.getJSONObject(i);val id=item.getString("id");val kind=item.getString("kind");val r=numbers(item,"rect",4)
            require(id.isNotBlank() && kind in setOf("visual","text") && item.opt("required")==true) { "invalid_region" }
            require(r.all { it in 0.0..1.0 } && r[0]<r[2] && r[1]<r[3]) { "invalid_region_rect" }
            val x=floor(r[0]*template.width+.5).toInt(); val y=floor(r[1]*template.height+.5).toInt()
            val box=Box(x,y,floor(r[2]*template.width+.5).toInt()-x,floor(r[3]*template.height+.5).toInt()-y)
            require(box.w>0 && box.h>0 && (kind!="visual" || min(box.w,box.h)>=8)) { "invalid_region_size" }
            val label=if(kind=="text")normalizeText(item.getString("text")) else null
            require(kind!="text" || !label.isNullOrEmpty()) { "invalid_region_text" }
            val coverX=floor(r[0]*template.width).toInt();val coverY=floor(r[1]*template.height).toInt()
            Region(id,kind,item.getBoolean("required"),box,label,Box(coverX,coverY,ceil(r[2]*template.width).toInt()-coverX,ceil(r[3]*template.height).toInt()-coverY))
        }
        require(regions.map { it.id }.toSet().size==regions.size && regions.any { it.kind=="visual" && it.required }) { "invalid_visual_regions" }
        require(regions.count {it.kind=="visual"}==1) {"unsupported_multiple_visual_regions"}
        validateCoverage(template,regions)
        val rawRelations=data.optJSONArray("relations") ?: JSONArray()
        val relations=(0 until rawRelations.length()).map { i ->
            val r=rawRelations.getJSONObject(i);val visual=r.getString("visual");val text=r.getString("text");val direction=r.getString("direction")
            require((!r.has("max_gap") || r.opt("max_gap") is Number) && (!r.has("max_alignment") || r.opt("max_alignment") is Number)) {"invalid_relation"}
            val gap=r.optDouble("max_gap",2.0);val alignment=r.optDouble("max_alignment",.5)
            require(regions.any { it.id==visual && it.kind=="visual" } && regions.any { it.id==text && it.kind=="text" }) { "invalid_relation_region" }
            require(direction in setOf("right","left","above","below") && gap.isFinite() && gap>=0 && alignment.isFinite() && alignment>=0) { "invalid_relation" }
            Relation(visual,text,direction,gap,alignment)
        }
        require(regions.filter { it.kind=="text" && it.required }.all { r -> relations.any { it.text==r.id } }) { "unconstrained_text_region" }
        val tap=data.optJSONObject("tap") ?: throw IllegalArgumentException("invalid_tap")
        val tapRegion=tap.getString("region_id");val anchor=numbers(tap,"anchor",2)
        require(regions.any { it.id==tapRegion && it.required } && anchor.all { it in 0.0..1.0 }) { "invalid_tap" }
        return Descriptor(regions,relations,tapRegion,anchor)
    }

    /** Descriptor decomposition may move labels, but it cannot silently discard
     * another badge or changed text from the original reference. */
    private fun validateCoverage(template:Pixels,regions:List<Region>) {
        val border=mutableListOf<Int>()
        for(x in 0 until template.width) {border.add(template.argb[x]);border.add(template.argb[(template.height-1)*template.width+x])}
        for(y in 1 until template.height-1){border.add(template.argb[y*template.width]);border.add(template.argb[y*template.width+template.width-1])}
        val median=IntArray(3) { channel ->
            val shift=16-channel*8;val sorted=border.map {it shr shift and 255}.sorted()
            (sorted[(sorted.size-1)/2]+sorted[sorted.size/2])/2
        }
        var total=0;var omitted=0
        for(y in 0 until template.height)for(x in 0 until template.width) {
            val c=template.argb[y*template.width+x]
            val foreground=max(abs((c shr 16 and 255)-median[0]),max(abs((c shr 8 and 255)-median[1]),abs((c and 255)-median[2])))>=48
            if(foreground) {
                total++
                if(regions.none {x>=it.coverage.x-2 && x<it.coverage.right+2 && y>=it.coverage.y-2 && y<it.coverage.bottom+2})omitted++
            }
        }
        require(omitted<=max(8.0,.01*total)) { "descriptor_uncovered_foreground" }
    }

    @JvmStatic fun match(shot: Bitmap, template: Bitmap, options: JSONObject): JSONObject {
        val config=options.optJSONObject("image_match")
        val profile=config?.optString("profile",VERSION) ?: VERSION
        if(profile=="strict-v1") return StrictImageMatcher.match(shot,template,options)
        val started=System.nanoTime()
        val candidates=JSONArray();val quality=JSONObject();val timings=JSONObject()
        fun result(status:String,reason:String) = JSONObject().put("status",status).put("reason",reason)
            .put("matcher_version",VERSION).put("candidates",candidates).put("point",JSONObject.NULL)
            .put("quality",quality).put("timings",timings.put("total_ms",(System.nanoTime()-started)/1e6))
        if(options.has("image_match") && config==null)return result("invalid_template","invalid_image_match")
        if(config!=null && config.has("profile") && config.opt("profile") !is String)return result("invalid_template","invalid_match_profile")
        if(profile!=VERSION) return result("unsupported","unsupported_match_profile")
        val timeout=options.optDouble("timeout_ms",5000.0)
        val wallBudget=options.optDouble("deadline_ms",System.currentTimeMillis()+timeout)-System.currentTimeMillis()
        val budget=min(timeout,wallBudget)
        if(!budget.isFinite() || budget<=0) return result("unstable","deadline_exceeded")
        val deadline=started+(budget*1e6).toLong()
        fun check() {if(Thread.currentThread().isInterrupted || System.nanoTime()>=deadline)throw Expired()}
        try {
            check()
            if(config!=null) {
                val expected=config.optString("template_sha256","").lowercase()
                require(expected.matches(Regex("[0-9a-f]{64}")) && expected==options.optString("template_sha256","").lowercase()) { "template_hash_mismatch" }
            }
            val source=raster(template);val screen=raster(shot);val descriptor=descriptor(config,source)
            quality.put("valid",true).put("reason","ok").put("size",JSONArray(intArrayOf(source.width,source.height)))
                .put("descriptor_version",1)
            var searchBox=Box(0,0,screen.width,screen.height)
            options.optJSONArray("search_region")?.let {
                val r=numbers(options,"search_region",4)
                require(r.all { n -> n in 0.0..1.0 } && r[0]<r[2] && r[1]<r[3]) { "invalid_search_region" }
                val x=floor(r[0]*screen.width).toInt();val y=floor(r[1]*screen.height).toInt()
                searchBox=Box(x,y,ceil(r[2]*screen.width).toInt()-x,ceil(r[3]*screen.height).toInt()-y)
            }
            val prior=options.optJSONArray("source_size")?.let {
                val size=numbers(options,"source_size",2);require(size.all { n -> n>0 }) { "invalid_source_size" }
                sqrt(screen.width*screen.height/(size[0]*size[1]))
            } ?: 1.0
            val visualResults=linkedMapOf<String,List<Visual>>()
            var incomplete=false;val searchStarted=System.nanoTime()
            // A full template is also a useful candidate source. Its candidates
            // still undergo required-region and text checks below.
            val fullSearch=search(screen,source,searchBox,prior,::check)
            incomplete=incomplete || fullSearch.incomplete
            val full=Box(0,0,source.width,source.height)
            for(region in descriptor.regions.filter { it.kind=="visual" }) {
                check()
                val found=if(region.box==full)fullSearch else search(screen,source.crop(region.box),searchBox,prior,::check)
                incomplete=incomplete || found.incomplete
                val all=found.candidates.filter {it.accepted}.toMutableList()
                for(hit in fullSearch.candidates.filter {it.accepted}) {
                    val scale=hit.box.w.toDouble()/source.width
                    val b=Box(hit.box.x+floor(region.box.x*scale+.5).toInt(),hit.box.y+floor(region.box.y*scale+.5).toInt(),
                        floor(region.box.w*scale+.5).toInt(),floor(region.box.h*scale+.5).toInt())
                    if(min(b.w,b.h)>=8 && b.right<=screen.width && b.bottom<=screen.height) {
                        val t=resize(source.crop(region.box),b.w,b.h)
                        val verification=StrictImageMatcher.verifyAdaptiveArgb(b.w,b.h,t.argb,screen.crop(b).argb,hit.score)
                        if(verification.accepted) all.add(Visual(b,hit.score,verification.json()))
                    }
                }
                val unique=mutableListOf<Visual>()
                for(hit in all.sortedByDescending { it.score })if(unique.none { same(it.box,hit.box) })unique.add(hit)
                visualResults[region.id]=unique
            }
            timings.put("search_ms",(System.nanoTime()-searchStarted)/1e6)
            val requiredVisual=descriptor.regions.filter { it.kind=="visual" && it.required }
            if(fullSearch.candidates.none {it.accepted} && requiredVisual.any { visualResults[it.id].isNullOrEmpty() })return result(if(incomplete)"unstable" else "not_found",if(incomplete)"search_incomplete" else "no_verified_candidate")
            var combinations=listOf(emptyMap<String,Visual>())
            for(region in requiredVisual) {
                val next=mutableListOf<Map<String,Visual>>()
                for(combo in combinations)for(hit in visualResults[region.id].orEmpty()) {
                    check()
                    if(combo.values.any { same(it.box,hit.box) })continue
                    next.add(combo+(region.id to hit))
                    if(next.size>64)return result("unstable","candidate_overflow")
                }
                combinations=next
            }
            val accepted=mutableListOf<Combination>();val ocrCache=mutableMapOf<Box,List<Text>>();val ocrStarted=System.nanoTime()
            // An accepted whole template already checks the exact original
            // label pixels. OCR is only needed for independently reflowed parts.
            for(hit in fullSearch.candidates.filter {it.accepted}) {
                val boxes=linkedMapOf<String,Box>();val matches=linkedMapOf<String,JSONObject>()
                val sx=hit.box.w.toDouble()/source.width;val sy=hit.box.h.toDouble()/source.height
                for(region in descriptor.regions) {
                    val b=Box(hit.box.x+floor(region.box.x*sx+.5).toInt(),hit.box.y+floor(region.box.y*sy+.5).toInt(),
                        max(1,floor(region.box.w*sx+.5).toInt()),max(1,floor(region.box.h*sy+.5).toInt()))
                    boxes[region.id]=b
                    matches[region.id]=JSONObject().put("rect",b.json()).put("accepted",true).put("evidence","whole_template")
                }
                val tap=boxes.getValue(descriptor.tapRegion)
                accepted.add(Combination(matches,boxes,hit.score,doubleArrayOf(tap.x+min(tap.w-1.0,tap.w*descriptor.anchor[0]),tap.y+min(tap.h-1.0,tap.h*descriptor.anchor[1]))))
            }
            var ocrUnknown:String?=null
            for(visuals in combinations) {
                check()
                if(accepted.any { known -> visuals.all { (id,hit) -> known.boxes[id]?.let {same(it,hit.box)} ?: false } })continue
                val boxes=visuals.mapValues { it.value.box }.toMutableMap()
                val matches=visuals.mapValues { (_,hit) -> JSONObject().put("rect",hit.box.json()).put("similarity",hit.score).put("metrics",hit.metrics).put("accepted",true) }.toMutableMap()
                var valid=true
                val labelMatches=linkedMapOf<String,List<Text>>()
                for(region in descriptor.regions.filter { it.kind=="text" && it.required }) {
                    val relations=descriptor.relations.filter { it.text==region.id }
                    var roi:Box?=null
                    for(relation in relations) {
                        val vb=boxes[relation.visual] ?: continue
                        val pad=ceil((relation.gap+1)*min(vb.w,vb.h)).toInt()
                        val b=intersection(clip(Box(vb.x-pad,vb.y-pad,vb.w+2*pad,vb.h+2*pad),screen.width,screen.height),searchBox) ?: continue
                        roi=if(roi==null)b else union(roi,b)
                    }
                    if(roi==null){valid=false;break}
                    val textItems=try {ocrCache.getOrPut(roi) { recognize(screen,roi,deadline) }} catch(_:TimeoutException) {
                        ocrUnknown="ocr_deadline";valid=false;break
                    } catch(error:Exception) {
                        ocrUnknown=if(error.message=="invalid_ocr_contract")"invalid_ocr_contract" else "ocr_unavailable";valid=false;break
                    }
                    val adjacent=textItems.filter { item -> contained(item.box,searchBox) && relations.all { r ->
                        boxes[r.visual]?.let { vb -> relationPass(vb,item.box,r.direction,r.gap,r.alignment) } ?: false
                    }}
                    when(val evidence=textEvidence(adjacent,region.text!!)) {
                        "invalid_ocr_contract","ocr_low_confidence" -> {ocrUnknown=evidence;valid=false;break}
                    }
                    val possible=adjacent.filter { item -> item.score>=.90 && normalizeText(item.text)==region.text }
                    if(possible.isEmpty()){valid=false;break}
                    labelMatches[region.id]=possible
                }
                if(valid) {
                    val assignments=try {textAssignments(labelMatches)}catch(_:IllegalArgumentException){incomplete=true;emptyList()}
                    for(assignment in assignments) {
                        val comboBoxes=boxes.toMutableMap();val comboMatches=matches.toMutableMap()
                        for((id,item) in assignment) {
                            comboBoxes[id]=item.box
                            comboMatches[id]=JSONObject().put("rect",item.box.json()).put("text",normalizeText(item.text)).put("confidence",item.score).put("accepted",true)
                                .put("ocr_engine","mlkit-chinese").put("ocr_min_confidence",.90)
                        }
                        val tap=comboBoxes[descriptor.tapRegion] ?: continue
                        if(comboBoxes.values.any {!contained(it,searchBox)})continue
                        val point=doubleArrayOf(tap.x+min(tap.w-1.0,tap.w*descriptor.anchor[0]),tap.y+min(tap.h-1.0,tap.h*descriptor.anchor[1]))
                        val candidate=Combination(comboMatches,comboBoxes,visuals.values.minOf {it.score},point)
                        if(accepted.none {old -> sameRegionTargets(old.boxes,candidate.boxes)})accepted.add(candidate)
                    }
                }
            }
            timings.put("ocr_ms",(System.nanoTime()-ocrStarted)/1e6)
            accepted.forEach { item -> candidates.put(JSONObject().put("rect",item.box.json()).put("point",JSONArray(item.point))
                .put("similarity",item.score).put("accepted",true).put("region_matches",JSONObject(item.matches))) }
            check()
            if(incomplete)return result("unstable","search_incomplete")
            if(accepted.size>1)return result("ambiguous","multiple_verified_targets")
            if(ocrUnknown!=null)return result("unsupported",ocrUnknown!!)
            if(accepted.isEmpty())return result("not_found","no_verified_combination")
            val best=accepted.single()
            val visualOnly=descriptor.regions.all {it.kind=="visual"}
            if(visualOnly && fullSearch.candidates.any { rival ->
                !rival.accepted && !same(rival.box,best.box) && rival.score>=.90 && best.score-rival.score<.03 &&
                    rival.metrics.optDouble("edge_coverage",0.0)>=.80 && rival.metrics.optDouble("foreground_error",1.0)<=.16
            })return result("ambiguous","insufficient_margin")
            return result("matched","unique_verified_target").put("point",JSONArray(best.point)).put("rect",best.box.json()).put("region_matches",JSONObject(best.matches))
        } catch(_:Expired) {return result("unstable","deadline_exceeded")
        } catch(_:TimeoutException) {return result("unstable","ocr_deadline")
        } catch(_:InterruptedException) {Thread.currentThread().interrupt();return result("unstable","interrupted")
        } catch(error:IllegalArgumentException) {
            val reason=error.message ?: "invalid_descriptor"
            return result(if(reason in setOf("unsupported_descriptor_version","unsupported_multiple_visual_regions","invalid_ocr_contract"))"unsupported" else "invalid_template",reason)
        } catch(_:LinkageError) {return result("unsupported","native_dependency_unavailable")
        } catch(error:Exception) {return result("unsupported","adaptive_match_error:${error.javaClass.simpleName}")}
    }

    private fun clip(b:Box,width:Int,height:Int):Box {
        val x=b.x.coerceIn(0,width-1);val y=b.y.coerceIn(0,height-1)
        return Box(x,y,b.right.coerceIn(x+1,width)-x,b.bottom.coerceIn(y+1,height)-y)
    }
    private fun recognize(screen:Pixels,roi:Box,deadline:Long):List<Text> {
        val bitmap=screen.crop(roi).bitmap()
        return try {ocr.detectBitmap(bitmap,deadline).map { item ->
            require(item.confidence.isFinite() && item.bounds.width()>0 && item.bounds.height()>0) {"invalid_ocr_contract"}
            Text(item.text,item.confidence.toDouble(),Box(item.bounds.left+roi.x,item.bounds.top+roi.y,item.bounds.width(),item.bounds.height()))
        }} finally {bitmap.recycle()}
    }

    /** Covers the admissible size interval, with no unreported fixed scale cap. */
    private fun search(screen:Pixels,template:Pixels,roi:Box,prior:Double,check:()->Unit):Search {
        val minimum=8.0/min(template.width,template.height)
        val maximum=min(roi.w.toDouble()/template.width,roi.h.toDouble()/template.height)
        if(maximum<minimum)return Search(emptyList(),false)
        val scales=mutableListOf<Double>()
        var s=minimum
        while(s<maximum){scales.add(s);s*=1.12}
        scales.add(maximum)
        for(seed in doubleArrayOf(1.0,.95,1.05,.9,1.1,.85,1.15,.8,1.2)) {
            if(seed in minimum..maximum)scales.add(seed)
            if(seed*prior in minimum..maximum)scales.add(seed*prior)
        }
        val ordered=scales.distinct().sortedBy { min(abs(ln(it)),abs(ln(it/prior))) }
        val proposals=mutableListOf<Proposal>();val pyramid=mutableMapOf<Pair<Int,Int>,Mat>()
        val originalSearch=screen.crop(roi)
        var incomplete=false
        try {
            for(scale in ordered) {
                check()
                val w=floor(template.width*scale+.5).toInt();val h=floor(template.height*scale+.5).toInt()
                if(min(w,h)<8 || w>roi.w || h>roi.h)continue
                // Keep enough contour at the proposal level to avoid hundreds
                // of unrelated 8px NCC peaks. Original 8px targets still search
                // at full resolution; this only bounds downsampling.
                val factor=max(1.0,floor(min(max(1.0,max(roi.w,roi.h)/960.0),max(1.0,min(w,h)/32.0))*4)/4)
                val pw=max(1,floor(roi.w/factor+.5).toInt());val ph=max(1,floor(roi.h/factor+.5).toInt())
                val entry=pyramid.getOrPut(pw to ph) {resize(originalSearch,pw,ph).grayMat()}
                val tw=max(2,floor(w/factor+.5).toInt());val th=max(2,floor(h/factor+.5).toInt())
                val t=resize(template,tw,th);val tm=t.grayMat();val scores=Mat()
                try {
                    Imgproc.matchTemplate(entry,tm,scores,Imgproc.TM_CCOEFF_NORMED)
                    for(index in 0..20) {
                        check();val peak=Core.minMaxLoc(scores)
                        if(!peak.maxVal.isFinite() || peak.maxVal<if(factor>1.0).65 else .82)break
                        if(index==20){incomplete=true;break}
                        val x=peak.maxLoc.x.toInt();val y=peak.maxLoc.y.toInt()
                        proposals.add(Proposal(Box(roi.x+floor(x*roi.w.toDouble()/pw+.5).toInt(),roi.y+floor(y*roi.h.toDouble()/ph+.5).toInt(),w,h),peak.maxVal,scale))
                        val x0=max(0,x-max(1,tw/3));val y0=max(0,y-max(1,th/3))
                        val x1=min(scores.cols(),x+max(1,tw/3)+1);val y1=min(scores.rows(),y+max(1,th/3)+1)
                        val sub=scores.submat(Rect(x0,y0,x1-x0,y1-y0));try{sub.setTo(Scalar(-2.0))}finally{sub.release()}
                    }
                } finally {tm.release();scores.release()}
            }
            val independent=mutableListOf<Proposal>()
            for(p in proposals.sortedByDescending {it.score})if(independent.none {same(it.box,p.box)})independent.add(p)
            if(independent.size>64)incomplete=true
            val found=mutableListOf<Visual>()
            for(primary in independent.take(64)) {
                val basins=proposals.filter {same(primary.box,it.box)}.sortedByDescending {it.score}.distinctBy {it.box.w to it.box.h}
                var acceptedObject=false
                var strongestRejected:Visual?=null
                val tried=mutableSetOf<Pair<Int,Int>>()
                for(p in basins) {
                    val lo=max(minimum,p.scale/sqrt(1.12));val hi=min(maximum,p.scale*sqrt(1.12))
                    val breakpoints=mutableListOf(lo,hi,p.scale)
                    for(n in floor(template.width*lo-.5).toInt()..ceil(template.width*hi+.5).toInt())breakpoints.add((n+.5)/template.width)
                    for(n in floor(template.height*lo-.5).toInt()..ceil(template.height*hi+.5).toInt())breakpoints.add((n+.5)/template.height)
                    val stops=breakpoints.filter {it in lo..hi}.distinct().sorted()
                    val sizes=mutableSetOf<Pair<Int,Int>>()
                    for(scale in stops+stops.zipWithNext {a,b -> (a+b)/2})sizes.add(floor(template.width*scale+.5).toInt() to floor(template.height*scale+.5).toInt())
                    val refined=mutableListOf<Pair<Box,Double>>()
                    for((w,h) in sizes.sortedBy {abs(it.first-p.box.w)+abs(it.second-p.box.h)}) {
                        check();if(min(w,h)<8 || w>roi.w || h>roi.h || !tried.add(w to h))continue
                        val dx=abs(w-p.box.w)+3;val dy=abs(h-p.box.h)+3
                        val left=max(roi.x,p.box.x-dx);val top=max(roi.y,p.box.y-dy)
                        val right=min(roi.right,p.box.x+p.box.w+dx);val bottom=min(roi.bottom,p.box.y+p.box.h+dy)
                        if(right-left<w || bottom-top<h)continue
                        val area=Box(left,top,right-left,bottom-top);val patch=screen.crop(area);val scaled=resize(template,w,h)
                        val sm=patch.grayMat();val tm=scaled.grayMat();val scores=Mat()
                        try {
                            Imgproc.matchTemplate(sm,tm,scores,Imgproc.TM_CCOEFF_NORMED)
                            val peak=Core.minMaxLoc(scores)
                            if(!peak.maxVal.isFinite() || peak.maxVal<.82)continue
                            val box=Box(left+peak.maxLoc.x.toInt(),top+peak.maxLoc.y.toInt(),w,h)
                            refined.add(box to peak.maxVal)
                        } finally {sm.release();tm.release();scores.release()}
                    }
                    var checkedRejected=false
                    for((box,score) in refined.sortedByDescending {it.second}) {
                        check()
                        if(score<.92 && checkedRejected)break
                        val scaled=resize(template,box.w,box.h)
                        val v=StrictImageMatcher.verifyAdaptiveArgb(box.w,box.h,scaled.argb,screen.crop(box).argb,score)
                        if(v.accepted) {
                            val hit=Visual(box,score,v.json())
                            val old=found.indexOfFirst {same(it.box,box)}
                            if(old<0)found.add(hit) else if(found[old].score<hit.score)found[old]=hit
                            acceptedObject=true
                            break
                        }
                        checkedRejected=true
                        if(strongestRejected==null || strongestRejected!!.score<score)strongestRejected=Visual(box,score,v.json(),false)
                    }
                    if(acceptedObject)break
                }
                if(!acceptedObject && strongestRejected!=null)found.add(strongestRejected!!)
            }
            return Search(found,incomplete)
        } finally {pyramid.values.forEach {it.release()}}
    }
}
