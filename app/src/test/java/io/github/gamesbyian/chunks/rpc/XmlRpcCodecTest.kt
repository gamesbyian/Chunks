package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class XmlRpcCodecTest {
    @Test fun requestEscapesUntrustedParameters() {
        val request = XmlRpcCodec.request("d.multicall2", listOf("", "main", "d.name=", "A&B <C>"))
        assertTrue(request.contains("A&amp;B &lt;C&gt;"))
        assertTrue(request.contains("<methodName>d.multicall2</methodName>"))
        assertEquals(4, "<param>".toRegex().findAll(request).count())
    }

    @Test fun responseReadsMulticallRows() {
        val xml = """<methodResponse><params><param><value><array><data>
            <value><array><data>
                <value><string>ABCD</string></value>
                <value><string>Star Trek</string></value>
                <value><i8>100</i8></value>
            </data></array></value>
        </data></array></value></param></params></methodResponse>"""
        assertEquals(listOf(listOf("ABCD", "Star Trek", 100L)), XmlRpcCodec.response(xml))
    }

    @Test fun responseDecodesFaultAsFailure() {
        val xml = """<methodResponse><fault><value><struct>
           <member><name>faultCode</name><value><int>5</int></value></member>
           <member><name>faultString</name><value><string>Denied</string></value></member>
        </struct></value></fault></methodResponse>"""
        try {
            XmlRpcCodec.response(xml)
            fail("Expected fault")
        } catch (failure: RpcFault) {
            assertEquals("5", failure.code)
            assertEquals("Denied", failure.message)
        }
    }

    @Test fun responseRejectsXmlEntityExpansion() {
        val xml = """<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <methodResponse><params><param><value>&xxe;</value></param></params></methodResponse>"""
        try {
            XmlRpcCodec.response(xml)
            fail("DTD must be rejected")
        } catch (expected: Exception) { /* parser must reject unsafe DTD */ }
    }

    @Test fun methodNameRejectsInjection() {
        try {
            XmlRpcCodec.request("name</methodName>", emptyList())
            fail("Expected rejection")
        } catch (expected: IllegalArgumentException) { }
    }
}
