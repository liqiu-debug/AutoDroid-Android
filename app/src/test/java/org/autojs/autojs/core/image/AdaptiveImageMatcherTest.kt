package org.autojs.autojs.core.image

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Pure contract/math regressions; Android/JNI search has its own instrumentation suite. */
class AdaptiveImageMatcherTest {
    @Test fun halfPixelBilinearHasPortableHalfUpRounding() {
        val image=AdaptiveImageMatcher.Pixels(2,1,intArrayOf(0xff000000.toInt(),0xffffffff.toInt()))
        val resized=AdaptiveImageMatcher.resize(image,3,1)
        assertArrayEquals(intArrayOf(0xff000000.toInt(),0xff808080.toInt(),0xffffffff.toInt()),resized.argb)
        assertArrayEquals(image.argb,AdaptiveImageMatcher.resize(image,2,1).argb)
    }
    @Test fun nonSquareResizeUsesIndependentHalfPixelCoordinates() {
        val image=AdaptiveImageMatcher.Pixels(2,2,intArrayOf(0xff000000.toInt(),0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt()))
        val resized=AdaptiveImageMatcher.resize(image,1,1)
        assertEquals(0xff404040.toInt(),resized.argb.single())
    }
    @Test fun exactTextKeepsCasePunctuationAndNumbers() {
        assertEquals("云闪付 A1",AdaptiveImageMatcher.normalizeText("  云闪付\n A1  "))
        assertEquals("云闪付 A1",AdaptiveImageMatcher.normalizeText("云闪付\u00a0A1"))
        assertNotEquals(AdaptiveImageMatcher.normalizeText("A1"),AdaptiveImageMatcher.normalizeText("a1"))
        assertNotEquals(AdaptiveImageMatcher.normalizeText("云闪付"),AdaptiveImageMatcher.normalizeText("云闪付支付"))
        assertNotEquals(AdaptiveImageMatcher.normalizeText("1.0"),AdaptiveImageMatcher.normalizeText("10"))
        assertEquals("é",AdaptiveImageMatcher.normalizeText("e\u0301"))
    }
    @Test fun canonicalDigestHashesTheOriginalUtf8Bytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",AdaptiveImageMatcher.sha256("abc"))
        assertNotEquals(AdaptiveImageMatcher.sha256("{\"x\":1.0}"),AdaptiveImageMatcher.sha256("{\"x\":1}"))
    }
    @Test fun uncertainOcrCannotProveAbsence() {
        val box=AdaptiveImageMatcher.Box(50,50,80,20)
        assertEquals("ocr_low_confidence",AdaptiveImageMatcher.textEvidence(listOf(AdaptiveImageMatcher.Text("云闪付",.89,box)),"云闪付"))
        assertEquals("ocr_low_confidence",AdaptiveImageMatcher.textEvidence(emptyList(),"云闪付"))
        assertEquals("invalid_ocr_contract",AdaptiveImageMatcher.textEvidence(listOf(AdaptiveImageMatcher.Text("云闪付",Double.NaN,box)),"云闪付"))
        assertEquals("absent",AdaptiveImageMatcher.textEvidence(listOf(AdaptiveImageMatcher.Text("支付宝",.99,box)),"云闪付"))
        assertEquals("present",AdaptiveImageMatcher.textEvidence(listOf(AdaptiveImageMatcher.Text("云闪付",.99,box)),"云闪付"))
    }
    @Test fun multipleCorrectLabelsRemainDistinctCompleteCandidates() {
        val icon=AdaptiveImageMatcher.Box(100,100,40,40)
        val a=AdaptiveImageMatcher.Text("云闪付",.99,AdaptiveImageMatcher.Box(150,101,80,18))
        val b=AdaptiveImageMatcher.Text("云闪付",.99,AdaptiveImageMatcher.Box(150,123,80,18))
        val assignments=AdaptiveImageMatcher.textAssignments(mapOf("label" to listOf(a,b)))
        assertEquals(2,assignments.size)
        assertFalse(AdaptiveImageMatcher.sameRegionTargets(mapOf("icon" to icon,"label" to a.box),mapOf("icon" to icon,"label" to b.box)))
        assertEquals("present",AdaptiveImageMatcher.textEvidence(listOf(a,b),"云闪付"))
    }
    @Test fun aMissingRequiredLabelOrReusingOneLabelCannotCompleteACombination() {
        val a=AdaptiveImageMatcher.Text("云闪付",.99,AdaptiveImageMatcher.Box(150,101,80,18))
        assertTrue(AdaptiveImageMatcher.textAssignments(mapOf("label" to emptyList())).isEmpty())
        assertTrue(AdaptiveImageMatcher.textAssignments(linkedMapOf("label1" to listOf(a),"label2" to listOf(a))).isEmpty())
    }
    @Test fun RequiredTextAndClickTargetStayInsideTheUserSearchRegion() {
        val scope=AdaptiveImageMatcher.Box(100,100,100,100)
        assertTrue(AdaptiveImageMatcher.contained(AdaptiveImageMatcher.Box(100,100,20,20),scope))
        assertTrue(AdaptiveImageMatcher.contained(AdaptiveImageMatcher.Box(180,180,20,20),scope))
        assertFalse(AdaptiveImageMatcher.contained(AdaptiveImageMatcher.Box(180,180,21,20),scope))
        assertFalse(AdaptiveImageMatcher.contained(AdaptiveImageMatcher.Box(90,110,80,20),scope))
    }
    @Test fun adjacentTextRequiresDirectionDistanceAndAlignmentTogether() {
        val icon=AdaptiveImageMatcher.Box(100,100,40,40)
        assertTrue(AdaptiveImageMatcher.relationPass(icon,AdaptiveImageMatcher.Box(150,110,100,20),"right",2.0,.5))
        assertFalse(AdaptiveImageMatcher.relationPass(icon,AdaptiveImageMatcher.Box(10,110,80,20),"right",2.0,.5))
        assertFalse(AdaptiveImageMatcher.relationPass(icon,AdaptiveImageMatcher.Box(230,110,100,20),"right",2.0,.5))
        assertFalse(AdaptiveImageMatcher.relationPass(icon,AdaptiveImageMatcher.Box(150,160,100,20),"right",2.0,.5))
    }
    @Test fun relationIsInvariantUnderScreenDensity() {
        val icon=AdaptiveImageMatcher.Box(100,100,40,40);val text=AdaptiveImageMatcher.Box(150,110,100,20)
        fun twice(b:AdaptiveImageMatcher.Box)=AdaptiveImageMatcher.Box(b.x*2,b.y*2,b.w*2,b.h*2)
        assertEquals(AdaptiveImageMatcher.relationPass(icon,text,"right",2.0,.5),AdaptiveImageMatcher.relationPass(twice(icon),twice(text),"right",2.0,.5))
    }
    @Test fun allFourDirectionsUseTheSameConstraint() {
        val icon=AdaptiveImageMatcher.Box(100,100,40,40)
        assertTrue(AdaptiveImageMatcher.relationPass(icon,AdaptiveImageMatcher.Box(50,110,40,20),"left",2.0,.5))
        assertTrue(AdaptiveImageMatcher.relationPass(icon,AdaptiveImageMatcher.Box(110,50,20,40),"above",2.0,.5))
        assertTrue(AdaptiveImageMatcher.relationPass(icon,AdaptiveImageMatcher.Box(110,150,20,40),"below",2.0,.5))
    }
    @Test fun adaptiveSearchDoesNotRelaxTheForegroundVerifier() {
        fun glyph(cross:Boolean)=IntArray(32*32) { i ->
            val x=i%32;val y=i/32
            val on=if(cross)(abs(x-y)<=2 || abs(x+y-31)<=2) && x in 5..26 && y in 5..26
                else (x in 14..18 && y in 5..26) || (y in 14..18 && x in 5..26)
            if(on)0xff000000.toInt() else 0xffffffff.toInt()
        }
        val template=glyph(false)
        assertTrue(StrictImageMatcher.verifyAdaptiveArgb(32,32,template,template,1.0).accepted)
        assertFalse(StrictImageMatcher.verifyAdaptiveArgb(32,32,template,glyph(true),.99).accepted)
        assertFalse(StrictImageMatcher.verifyAdaptiveArgb(32,32,template,template,.919).accepted)
    }
}
