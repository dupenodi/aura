package com.drishti.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageTest {
    @Test
    fun detectsTheScriptARequestIsWrittenIn() {
        assertEquals(Language.Hindi, Language.detect("WhatsApp पर अम्मा को वीडियो कॉल करो"))
        assertEquals(Language.Marathi, Language.detect("मला फोनची अक्षरे मोठी करायची आहेत, वेळ"))
        assertEquals(Language.Tamil, Language.detect("ப்ளூடூத்தை ஆன் செய்"))
        assertEquals(Language.Telugu, Language.detect("వైఫై ఆన్ చేయి"))
        assertEquals(Language.Bengali, Language.detect("ব্লুটুথ চালু করো"))
        assertEquals(Language.Kannada, Language.detect("ವೈಫೈ ಆನ್ ಮಾಡು"))
        assertEquals(Language.Malayalam, Language.detect("വൈഫൈ ഓണാക്കൂ"))
        assertEquals(Language.Gujarati, Language.detect("વાઇફાઇ ચાલુ કરો"))
        assertEquals(Language.Punjabi, Language.detect("ਵਾਈਫਾਈ ਚਾਲੂ ਕਰੋ"))
        assertEquals(Language.Odia, Language.detect("ୱାଇଫାଇ ଚାଲୁ କର"))
    }

    @Test
    fun latinScriptIsLeftToTheCaller() {
        assertNull(Language.detect("turn on wifi"))
        assertNull(Language.detect("wifi on karo"))
    }

    @Test
    fun sarvamCodesMapBackIncludingBothOdiaSpellings() {
        assertEquals(Language.Odia, Language.fromSarvam("od-IN"))
        assertEquals(Language.Odia, Language.fromSarvam("or-IN"))
        assertEquals(Language.Hindi, Language.fromSarvam("hi-IN"))
    }
}
