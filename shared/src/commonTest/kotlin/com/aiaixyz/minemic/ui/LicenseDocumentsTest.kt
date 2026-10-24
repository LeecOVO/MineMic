package com.aiaixyz.minemic.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LicenseDocumentsTest {
    @Test
    fun separatesDocumentsWithoutChangingLicenseText() {
        val first = "  Copyright Example\n\nAll rights reserved."
        val second = "Patent grant\n   and conditions."
        val bundle = parseLicenseBundle("Introduction\n\n=====\nAbseil-LICENSE\n=====\n$first\n\n=====\nWebRTC-PATENTS\n=====\n$second\n")
        assertEquals("Introduction", bundle.introduction)
        assertEquals(2, bundle.documents.size)
        assertEquals(first, bundle.documents.first { it.source == "Abseil-LICENSE" }.rawText)
        assertEquals(second, bundle.documents.first { it.source == "WebRTC-PATENTS" }.rawText)
    }

    @Test
    fun unknownFormatsRemainReadableAndComplete() {
        val raw = "Copyright Example\n\nAn unfamiliar license without section markers."
        val document = parseLicenseBundle(raw).documents.single()
        assertEquals(raw, document.rawText)
        assertEquals("许可声明", document.title)
    }

    @Test
    fun supportsWindowsLineEndingsAndUnknownComponents() {
        val bundle = parseLicenseBundle("=====\r\nOther-LICENSE\r\n=====\r\nFirst\r\nsecond\r\n")
        assertEquals("Other-LICENSE", bundle.documents.single().title)
        assertEquals("First\nsecond", bundle.documents.single().rawText)
    }

    @Test
    fun reflowsHardWrapsAndCommentPrefixesWhileRetainingParagraphs() {
        assertEquals(listOf("Copyright Example", "Permission to use, copy and modify."),
            licenseParagraphs("/*\n * Copyright Example\n *\n * Permission to use,\n * copy and modify.\n */"))
    }

    @Test
    fun retainsActualLicenseListMarkersAndNumbering() {
        assertEquals(listOf("1. Redistribution", "* Retain copyright notices.", "(a) Keep this condition."),
            licenseParagraphs("1. Redistribution\n\n  * Retain copyright\n    notices.\n\n(a) Keep this condition."))
    }
}
