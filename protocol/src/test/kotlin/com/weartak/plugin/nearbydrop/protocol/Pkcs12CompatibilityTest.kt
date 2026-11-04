// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop.protocol

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Pkcs12CompatibilityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `accepts real JDK encrypted identity and public certificate truststore`() {
        val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val identity = File(folder.root, "identity.p12")
        val cert = File(folder.root, "public.cer")
        val trust = File(folder.root, "trust.p12")
        fun run(vararg args: String) {
            val process = ProcessBuilder(keytool.absolutePath, *args).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { result }
        }
        run("-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Nearby Drop Test Only",
            "-validity", "1", "-storetype", "PKCS12", "-keystore", identity.path, "-storepass", "test-only-password", "-noprompt")
        Pkcs12Envelope.validate(identity.readBytes())
        run("-exportcert", "-alias", "test", "-keystore", identity.path, "-storepass", "test-only-password", "-file", cert.path)
        run("-importcert", "-alias", "test", "-file", cert.path, "-keystore", trust.path, "-storetype", "PKCS12", "-storepass", "test-only-password", "-noprompt")
        Pkcs12Envelope.validate(trust.readBytes())
    }
}
