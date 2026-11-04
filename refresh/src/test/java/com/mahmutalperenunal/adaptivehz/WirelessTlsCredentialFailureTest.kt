package com.mahmutalperenunal.adaptivehz

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessTlsCredentialFailureTest {
    @Test
    fun `device certificate rejection requests pairing again`() {
        assertTrue(isRejectedWirelessAdbCredential(
            SSLProtocolException("error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN")))
        assertTrue(isRejectedWirelessAdbCredential(IOException("connect failed",
            SSLHandshakeException("Received fatal alert: unknown_ca"))))
    }

    @Test
    fun `network and non credential TLS failures preserve pairing`() {
        assertFalse(isRejectedWirelessAdbCredential(SocketTimeoutException("Read timed out")))
        assertFalse(isRejectedWirelessAdbCredential(IOException("Connection refused")))
        assertFalse(isRejectedWirelessAdbCredential(SSLHandshakeException("Connection reset by peer")))
        assertFalse(isRejectedWirelessAdbCredential(IOException("certificate_unknown")))
    }
}
