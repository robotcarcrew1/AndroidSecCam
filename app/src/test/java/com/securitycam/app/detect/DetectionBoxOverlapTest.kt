package com.securitycam.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionBoxOverlapTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = Detection(
        group = DetectionGroup.VEHICLE, label = "car", score = 0.9f,
        left = left, top = top, right = right, bottom = bottom,
    )

    @Test
    fun `identical boxes have overlap of 1`() {
        val a = box(0f, 0f, 100f, 100f)
        val b = box(0f, 0f, 100f, 100f)
        assertEquals(1f, a.boxOverlap(b), 0.001f)
    }

    @Test
    fun `disjoint boxes have overlap of 0`() {
        val a = box(0f, 0f, 10f, 10f)
        val b = box(200f, 200f, 210f, 210f)
        assertEquals(0f, a.boxOverlap(b), 0.001f)
    }

    @Test
    fun `half-overlapping boxes give a partial score`() {
        val a = box(0f, 0f, 100f, 100f)
        val b = box(50f, 0f, 150f, 100f) // shifted right by half its width
        // intersection = 50x100 = 5000, union = 10000+10000-5000 = 15000 -> 1/3
        assertEquals(1f / 3f, a.boxOverlap(b), 0.01f)
    }

    @Test
    fun `slightly shifted box for the same still object stays above typical threshold`() {
        val a = box(100f, 100f, 300f, 300f)
        val b = box(105f, 98f, 305f, 302f) // small jitter, same physical object
        assertTrue(a.boxOverlap(b) > 0.8f)
    }

    @Test
    fun `a different object in a distinctly different position scores low`() {
        val parkedCar = box(50f, 200f, 250f, 350f)
        val newCarElsewhere = box(400f, 150f, 600f, 300f)
        assertTrue(parkedCar.boxOverlap(newCarElsewhere) < 0.1f)
    }

    @Test
    fun `overlap is symmetric`() {
        val a = box(10f, 10f, 90f, 90f)
        val b = box(40f, 40f, 120f, 120f)
        assertEquals(a.boxOverlap(b), b.boxOverlap(a), 0.0001f)
    }
}
