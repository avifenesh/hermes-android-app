package dev.avifenesh.hermes

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message reports open over the conversation that shows the message: mobile chats and other-app sessions. */
class SupportRoutingTest {
    private fun chatMessage(chat: String) = Msg("m1", chat, "agent", "text", "reply", JSONObject(), 1.0, 1)

    @Test fun chatMessageOpensOverItsOwnChatOnly() {
        val m = chatMessage("c1")
        assertTrue(Support.owns(Route.ChatR("c1"), m))
        assertFalse("an outgoing animated chat must not open actions over another chat", Support.owns(Route.ChatR("c2"), m))
    }

    @Test fun sessionMessageOpensOverItsOwnSession() {
        // SessionScreen builds rows with liveMessage(), whose chatId is the session id.
        val m = liveMessage(JSONObject().put("role", "assistant").put("text", "Session reply").put("position", 4), "s1")
        assertTrue("other-app session replies are reportable in place", Support.owns(Route.SessR("s1", "Telegram", "telegram"), m))
        assertFalse(Support.owns(Route.SessR("s2", "Other", "telegram"), m))
        assertFalse("a session id is not a mobile chat id", Support.owns(Route.ChatR("s1"), m.copy(chatId = "c1")))
    }

    @Test fun noOtherScreenOpensMessageActions() {
        val m = chatMessage("c1")
        for (route in listOf(null, Route.RunR("c1"), Route.ActR("a", "c1"), Route.JobR("c1"), Route.TalkR, Route.PrivacyR)) {
            assertFalse("$route", Support.owns(route, m))
        }
    }
}
