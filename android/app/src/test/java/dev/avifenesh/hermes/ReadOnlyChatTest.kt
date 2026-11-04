package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A mirrored group conversation is read-only on the phone; the notice says where to reply. */
class ReadOnlyChatTest {
    @Test fun groupMirrorsSayWhereToReply() {
        assertEquals("Group chat · read-only. Reply in Telegram.", readOnlyNotice("telegram:group"))
        assertEquals("Group chat · read-only. Reply in Telegram.", readOnlyNotice("telegram:forum"))
        assertEquals("Group chat · read-only. Reply in Telegram.", readOnlyNotice("telegram:supergroup"))
        assertEquals("Channel · read-only. Reply in Telegram.", readOnlyNotice("telegram:channel"))
    }

    @Test fun otherSurfacesAndUnknownKinds() {
        assertEquals("Group chat · read-only. Reply in Discord.", readOnlyNotice("discord:group"))
        assertEquals("Shared chat · read-only. Reply in Telegram.", readOnlyNotice("telegram:thread"))
        assertEquals("Shared chat · read-only. Reply where it started.", readOnlyNotice("shared"))
    }

    @Test fun ordinaryChatsAreWritable() {
        assertNull(readOnlyNotice(""))
        assertNull(readOnlyNotice("   "))
    }
}
