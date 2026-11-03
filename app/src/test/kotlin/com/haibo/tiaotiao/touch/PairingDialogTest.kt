package com.haibo.tiaotiao.touch

import org.junit.Assert.*
import org.junit.Test

class PairingDialogTest {
    private fun fields(code: String="012345",address: String="192.168.0.6:43210")=listOf(
        PairingDialog.Field(PairingDialog.CODE,code),PairingDialog.Field(PairingDialog.ADDRESS,address))
    @Test fun readsOnlyStructuredPairingFieldsAndPreservesLeadingZero() {
        val result=PairingDialog.parse(PairingDialog.SETTINGS,fields())!!
        assertEquals("012345",result.code);assertEquals(43210,result.port)
        assertFalse(result.toString().contains("012345"))
        assertFalse(fields().toString().contains("012345"))
    }
    @Test fun foreignWindowOrForeignFieldCannotSupplyPairingCode() {
        assertNull(PairingDialog.parse("example.app",fields()))
        assertNull(PairingDialog.parse(PairingDialog.SETTINGS,listOf(
            PairingDialog.Field(PairingDialog.CODE,"012345","example.app"),fields()[1])))
    }
    @Test fun arbitrarySixDigitTextAndMissingAddressAreRejected() {
        assertNull(PairingDialog.parse(PairingDialog.SETTINGS,listOf(PairingDialog.Field("android:id/summary","012345"),fields()[1])))
        assertNull(PairingDialog.parse(PairingDialog.SETTINGS,fields().take(1)))
    }
    @Test fun passwordsAndEditableFieldsAreNeverRead() {
        for(field in listOf(PairingDialog.Field(PairingDialog.CODE,"012345",password=true),PairingDialog.Field(PairingDialog.CODE,"012345",editable=true))) {
            assertNull(PairingDialog.parse(PairingDialog.SETTINGS,listOf(field,fields()[1])))
        }
    }
    @Test fun malformedOrAmbiguousDialogIsRejected() {
        for(address in listOf("192.168.0.6:0","192.168.0.6:65536","999.0.0.1:12345","example.com:12345")) {
            assertNull(PairingDialog.parse(PairingDialog.SETTINGS,fields(address=address)))
        }
        assertNull(PairingDialog.parse(PairingDialog.SETTINGS,fields()+PairingDialog.Field(PairingDialog.CODE,"654321")))
    }
}
