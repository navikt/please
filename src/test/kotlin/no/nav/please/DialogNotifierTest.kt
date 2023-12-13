package no.nav.please

import io.kotest.core.spec.style.StringSpec
import no.nav.please.varsler.DialogNotifier

class DialogNotifierTest : StringSpec({

    "should serialize message" {
        val subscriptionKey = "12345678910"
        val messageToSend = """{ "eventType": "NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV", "subscriptionKey": "$subscriptionKey" }"""
        val record = messageToSend
        DialogNotifier.notifySubscribers(record)
    }

})
