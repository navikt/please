package no.nav.please.plugins.redis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.please.varsler.Subscription
import no.nav.please.varsler.TicketStore
import no.nav.please.varsler.WellFormedTicket
import redis.clients.jedis.JedisPooled

class RedisTicketStore(val jedis: JedisPooled): TicketStore {
    override fun getSubscription(ticket: WellFormedTicket): Subscription? {
        return jedis[ticket.value]?.let { Json.decodeFromString<Subscription>(it) }
    }

    override fun addSubscription(ticket: WellFormedTicket, subscription: Subscription) {
        jedis.setex(ticket.value, 3600*6, Json.encodeToString(subscription))
    }

    override fun removeSubscription(ticket: WellFormedTicket) {
        jedis.del(ticket.value)
    }

}