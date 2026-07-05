package com.securitycam.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectionGroupTest {
    @Test
    fun `person maps to HUMAN`() {
        assertEquals(DetectionGroup.HUMAN, DetectionGroup.fromLabel("person"))
    }

    @Test
    fun `car truck bus map to VEHICLE`() {
        assertEquals(DetectionGroup.VEHICLE, DetectionGroup.fromLabel("car"))
        assertEquals(DetectionGroup.VEHICLE, DetectionGroup.fromLabel("truck"))
        assertEquals(DetectionGroup.VEHICLE, DetectionGroup.fromLabel("bus"))
        assertEquals(DetectionGroup.VEHICLE, DetectionGroup.fromLabel("bicycle"))
    }

    @Test
    fun `dog cat bird map to ANIMAL`() {
        assertEquals(DetectionGroup.ANIMAL, DetectionGroup.fromLabel("dog"))
        assertEquals(DetectionGroup.ANIMAL, DetectionGroup.fromLabel("cat"))
        assertEquals(DetectionGroup.ANIMAL, DetectionGroup.fromLabel("bird"))
    }

    @Test
    fun `label is case-insensitive and trims whitespace`() {
        assertEquals(DetectionGroup.HUMAN, DetectionGroup.fromLabel(" Person "))
        assertEquals(DetectionGroup.HUMAN, DetectionGroup.fromLabel("PERSON"))
    }

    @Test
    fun `unmapped labels return null`() {
        assertNull(DetectionGroup.fromLabel("toaster"))
        assertNull(DetectionGroup.fromLabel("chair"))
        assertNull(DetectionGroup.fromLabel(""))
    }
}
