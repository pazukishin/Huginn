package dev.huginn.data.smb

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbErrorMessagesTest {

    @Test
    fun `host desconocido sugiere revisar el host`() {
        assertEquals(dev.huginn.R.string.smb_err_host, SmbErrorMessages.describeRes(UnknownHostException("x")))
    }

    @Test
    fun `sin ruta avisa de la red`() {
        assertEquals(dev.huginn.R.string.smb_err_route, SmbErrorMessages.describeRes(NoRouteToHostException()))
    }

    @Test
    fun `conexion rechazada avisa de smb`() {
        assertEquals(dev.huginn.R.string.smb_err_refused, SmbErrorMessages.describeRes(ConnectException()))
    }

    @Test
    fun `timeout avisa de tiempo de espera`() {
        assertEquals(dev.huginn.R.string.smb_err_timeout, SmbErrorMessages.describeRes(SocketTimeoutException()))
    }

    @Test
    fun `error generico no filtra detalles raros`() {
        assertEquals(dev.huginn.R.string.smb_err_generic, SmbErrorMessages.describeRes(IOException("smb://host/share")))
    }

    @Test
    fun `los fallos de red se reintentan y los de credenciales no`() {
        assertTrue(SmbErrorMessages.isRetryable(SocketTimeoutException()))
        assertTrue(SmbErrorMessages.isRetryable(ConnectException()))
        assertTrue(SmbErrorMessages.isRetryable(NoRouteToHostException()))
        assertFalse(SmbErrorMessages.isRetryable(IOException("object not exist")))
        assertFalse(SmbErrorMessages.isRetryable(IOException("bad network name")))
    }
}
