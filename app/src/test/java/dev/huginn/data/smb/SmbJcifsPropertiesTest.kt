package dev.huginn.data.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class SmbJcifsPropertiesTest {

    @Test
    fun `por defecto exige SMB2 o superior`() {
        val props = SmbJcifsProperties.build(smb1 = false)
        assertEquals("SMB202", props.getProperty("jcifs.smb.client.minVersion"))
        assertEquals("SMB311", props.getProperty("jcifs.smb.client.maxVersion"))
    }

    @Test
    fun `el opt-in permite SMB1`() {
        val props = SmbJcifsProperties.build(smb1 = true)
        assertEquals("SMB1", props.getProperty("jcifs.smb.client.minVersion"))
        // Sigue aceptando hasta SMB3.1.1 cuando el servidor lo ofrezca.
        assertEquals("SMB311", props.getProperty("jcifs.smb.client.maxVersion"))
    }

    @Test
    fun `los timeouts de red estan configurados`() {
        val props = SmbJcifsProperties.build(smb1 = false)
        assertEquals(
            SmbJcifsProperties.IO_TIMEOUT_MS.toString(),
            props.getProperty("jcifs.smb.client.responseTimeout"),
        )
        assertEquals(
            SmbJcifsProperties.IO_TIMEOUT_MS.toString(),
            props.getProperty("jcifs.smb.client.connTimeout"),
        )
    }
}
