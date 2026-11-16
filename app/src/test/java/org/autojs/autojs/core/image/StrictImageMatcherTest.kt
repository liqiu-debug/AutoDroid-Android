package org.autojs.autojs.core.image

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import javax.imageio.ImageIO

/** Exercises the production foreground/contour/colour verifier without Android
 * graphics stubs or native JNI. End-to-end image search additionally needs an
 * Android runtime, while compileAppDebugKotlin checks the actual OpenCV bridge.
 */
class StrictImageMatcherTest {
    private fun glyph(color: Int=0xff000000.toInt(),cross: Boolean=false): IntArray = IntArray(32*32) { i ->
        val x=i%32;val y=i/32
        val ink=if(cross) (abs(x-y)<=2 || abs(x+y-31)<=2) && x in 5..26 && y in 5..26
                else ((x in 14..18 && y in 5..26) || (y in 14..18 && x in 5..26))
        if(ink)color else 0xffffffff.toInt()
    }
    @Test fun identicalGlyphPassesWithExactForegroundAndContours() {
        val a=glyph();val v=StrictImageMatcher.verifyArgb(32,32,a,a,1.0)
        assertTrue(v.accepted);assertEquals(1.0,v.recall,0.0);assertEquals(0.0,v.foregroundError,0.0)
    }
    @Test fun highNccDoesNotAuthorizeDifferentContour() {
        val v=StrictImageMatcher.verifyArgb(32,32,glyph(),glyph(cross=true),.99)
        assertFalse(v.accepted);assertTrue(v.recall<.88 || v.precision<.88 || v.foregroundError>.12 || v.tileError>.22)
    }
    @Test fun equalSilhouetteWithSwappedColourIsRejectedSpatially() {
        val v=StrictImageMatcher.verifyArgb(32,32,glyph(0xffff0000.toInt()),glyph(0xff00ff00.toInt()),1.0)
        assertFalse(v.accepted);assertNotNull(v.colorError);assertTrue(v.colorError!!>.08)
    }
    @Test fun flatRegionsCannotPassEvenWhenNccIsOne() {
        val blank=IntArray(32*32) { 0xffffffff.toInt() }
        assertFalse(StrictImageMatcher.verifyArgb(32,32,blank,blank,1.0).accepted)
    }
    @Test fun nccBelowProfileCannotPassPerfectContours() {
        val a=glyph();assertFalse(StrictImageMatcher.verifyArgb(32,32,a,a,.919).accepted)
    }
    @Test fun smallForegroundCannotBeHiddenByLargeMatchingBackground() {
        val template=IntArray(96*48) { 0xffffffff.toInt() };val candidate=template.copyOf()
        val a=glyph();val b=glyph(cross=true)
        for(y in 0..31)for(x in 0..31){template[(y+8)*96+x+32]=a[y*32+x];candidate[(y+8)*96+x+32]=b[y*32+x]}
        assertFalse(StrictImageMatcher.verifyArgb(96,48,template,candidate,.99).accepted)
    }
    @Test fun sharedLabelAndBorderCannotHideOneChangedDigit() {
        fun pixels(name: String): IntArray = javaClass.getResourceAsStream("/image_click/$name").use { stream ->
            requireNotNull(stream)
            val image = ImageIO.read(stream)
            image.getRGB(0,0,image.width,image.height,null,0,image.width)
        }
        val one=pixels("order-1-thin.png"); val two=pixels("order-2-thin.png")
        for ((template,wrong) in listOf(one to two,two to one)) {
            val mismatch=StrictImageMatcher.verifyArgb(160,60,template,wrong,.98)
            assertFalse(mismatch.accepted); assertTrue(mismatch.localError>.22)
            assertTrue(StrictImageMatcher.verifyArgb(160,60,template,template,1.0).accepted)
        }
    }
}
