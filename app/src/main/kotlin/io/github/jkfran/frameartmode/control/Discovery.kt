package io.github.jkfran.frameartmode.control

import io.github.jkfran.frameartmode.tv.DeviceInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A Samsung TV that answered on the local network. */
data class FoundTv(val host: String, val info: DeviceInfo)

/**
 * Finds Samsung TVs by asking every address of the local subnet for `/api/v2/`.
 * [probe] returns the device info at an address, or null.
 */
class Discovery(
    private val probe: suspend (host: String) -> DeviceInfo?,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    /** Frames first, then by address. */
    suspend fun scan(hosts: List<String>): List<FoundTv> = coroutineScope {
        val permits = Semaphore(parallelism)
        hosts.map { host ->
            async { permits.withPermit { probe(host)?.let { FoundTv(host, it) } } }
        }.awaitAll()
            .filterNotNull()
            .sortedWith(compareBy<FoundTv>({ !it.info.frameTvSupport }, { Ipv4.parse(it.host) ?: Long.MAX_VALUE }))
    }

    companion object {
        const val DEFAULT_PARALLELISM = 48

        /** Subnets larger than this are narrowed to the /24 around the device. */
        private const val WIDEST_PREFIX = 24

        /**
         * The addresses to probe around [address] (dotted IPv4) with [prefixLength]: the whole
         * subnet, or the /24 holding the device when the subnet is larger. The network, broadcast
         * and own addresses are left out.
         */
        fun hostsAround(address: String, prefixLength: Int): List<String> {
            val own = Ipv4.parse(address) ?: return emptyList()
            if (prefixLength !in 0..30) return emptyList()
            val prefix = maxOf(prefixLength, WIDEST_PREFIX)
            val size = 1L shl (32 - prefix)
            val network = own and (0xFFFFFFFFL shl (32 - prefix) and 0xFFFFFFFFL)
            return (1 until size - 1)
                .map { network + it }
                .filter { it != own }
                .map(Ipv4::format)
        }
    }
}

/** Dotted IPv4 addresses as unsigned 32-bit values. */
object Ipv4 {
    fun parse(text: String): Long? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val octet = part.toInt()
            if (octet > 255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun isValid(text: String): Boolean = parse(text) != null

    fun format(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }
}
