package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A mirrored group conversation is read-only on the phone; the notice says where to reply. */
class ReadOnlyChatTest {
    @Before fun englishStrings() = TestStrings.install()

    @Test fun groupMirrorsSayWhereToReply() {
        assertEquals("Group chat · read-only. Reply in \u2068Telegram\u2069.", readOnlyNotice("telegram:group"))
        assertEquals("Group chat · read-only. Reply in \u2068Telegram\u2069.", readOnlyNotice("telegram:forum"))
        assertEquals("Group chat · read-only. Reply in \u2068Telegram\u2069.", readOnlyNotice("telegram:supergroup"))
        assertEquals("Channel · read-only. Reply in \u2068Telegram\u2069.", readOnlyNotice("telegram:channel"))
    }

    @Test fun otherSurfacesAndUnknownKinds() {
        assertEquals("Group chat · read-only. Reply in \u2068Discord\u2069.", readOnlyNotice("discord:group"))
        assertEquals("Shared chat · read-only. Reply in \u2068Telegram\u2069.", readOnlyNotice("telegram:thread"))
        assertEquals("Shared chat · read-only. Reply where it started.", readOnlyNotice("shared"))
    }

    @Test fun ordinaryChatsAreWritable() {
        assertNull(readOnlyNotice(""))
        assertNull(readOnlyNotice("   "))
    }

    @Test fun readOnlyChatsOfferNoSessionCommands() {
        // New session, Undo, Compress, Rename would run in a private session (and post into the mirror); the server
        // refuses them and the reads, so the menu is not offered at all.
        fun chat(kind: String = "chat", readonly: String = "") = Chat("h-1", "Team", kind, 0.0, 0, false, false, "", "", readonly = readonly)
        assertEquals(true, chat().takesSessionCommands())
        assertEquals(false, chat(readonly = "telegram:group").takesSessionCommands())
        assertEquals(false, chat(kind = "digest").takesSessionCommands())
    }
}
