package com.example.assignment2.util

object BssidUtil {
    /**
     * Calculates the "prime" BSSID by taking the first 5 octets as is,
     * and for the 6th octet, taking its first hex digit and appending 'X'.
     * Colons are preserved in the output.
     * Example: "1c:28:1f:61:df:1a" -> "1c:28:1f:61:df:1X"
     * Example: "1c:28:1f:61:df:12" -> "1c:28:1f:61:df:1X"
     * Returns null if the BSSID format is invalid (e.g., not 6 octets).
     */
    fun calculateBssidPrime(fullBssid: String): String? {
        val octets = fullBssid.split(':')

        if (octets.size != 6) {
            return null // Invalid BSSID format, should have 6 octets
        }

        val validOctets = octets.map {
            if (it.length == 2) {
                it.lowercase()
            } else {
                return null // Each octet must be 2 characters long
            }
        }

        // Take the first 5 octets directly
        val firstFiveOctets = validOctets.subList(0, 5)

        // Modify the 6th octet
        val sixthOctetOriginal = validOctets[5]
        if (sixthOctetOriginal.isEmpty()) { // Should not happen if length check passed, but good for safety
            return null
        }
        val modifiedSixthOctet = sixthOctetOriginal[0].toString().lowercase() + "X"

        // Join them back with colons
        return (firstFiveOctets + modifiedSixthOctet).joinToString(":")
    }
}
