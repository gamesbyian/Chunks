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

    @Test fun requestSerializesActualBinaryTorrentBytesNotUtf8Text() {
        val bytes = byteArrayOf(0, 1, 2, -1, -128, 65)
        val xml = XmlRpcCodec.request("load.raw_verbose", listOf("", bytes, "d.directory_base.set=/tmp"))
        val base64 = Regex("<base64>([^<]+)</base64>").find(xml)!!.groupValues[1]
        assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(base64))
        assertTrue(xml.contains("<methodName>load.raw_verbose</methodName>"))
        assertTrue(xml.contains("d.directory_base.set=/tmp"))
    }

    @Test fun responseDecodesBinaryAndRejectsMalformedOrOversizedPayloads() {
        val xml = "<methodResponse><params><param><value><base64>AAEC/w==</base64></value></param></params></methodResponse>"
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), XmlRpcCodec.response(xml) as ByteArray)
        val bad = xml.replace("AAEC/w==", "not-valid%")
        try {
            XmlRpcCodec.response(bad)
            fail("Must refuse non-base64 binary data")
        } catch (_: IllegalArgumentException) { }
        try {
            XmlRpcCodec.request("load.raw_verbose", listOf(ByteArray(2_097_153)))
            fail("Must refuse oversized torrent metainfo")
        } catch (_: IllegalArgumentException) { }
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
    private fun response(value: String): String =
        "<methodResponse><params><param>$value</param></params></methodResponse>"

    @Test fun refusesPathologicallyDeepButSmallXmlBeforeConstructingDom() {
        val nested = buildString {
            repeat(60) { append("<value><array><data>") }
            append("<value><string>safe</string></value>")
            repeat(60) { append("</data></array></value>") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            XmlRpcCodec.response(response(nested))
        }
    }

    @Test fun rejectsSingleOversizedArrayWithoutTruncation() {
        val values = "<value><int>1</int></value>".repeat(12_001)
        val xml = response("<value><array><data>$values</data></array></value>")
        val failure = assertThrows(IllegalArgumentException::class.java) {
            XmlRpcCodec.response(xml)
        }
        assertTrue(failure.message.orEmpty().contains("array"))
    }

    @Test fun rejectsOverlongStringsAndStructKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            XmlRpcCodec.response(response(
                "<value><string>${"x".repeat(262_145)}</string></value>"
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XmlRpcCodec.response(response(
                "<value><struct><member><name>${"a".repeat(257)}</name>" +
                    "<value><int>1</int></value></member></struct></value>"
            ))
        }
    }

    @Test fun nestedButOrdinaryArrayAndFaultRemainSupported() {
        val xml = response("<value><array><data>" +
            "<value><array><data><value><string>ok</string></value>" +
            "</data></array></value></data></array></value>")
        assertEquals(listOf(listOf("ok")), XmlRpcCodec.response(xml))
        responseDecodesFaultAsFailure()
    }

    @Test fun malformedArrayCannotBecomeAnEmptyOrPartialInventory() {
        for (array in listOf(
            "<array/>",
            "<array><unexpected/></array>",
            "<array><data/><data/></array>",
            "<array><data/><unexpected/></array>",
            "<array><data><unexpected/></data></array>",
            "<array><data><value><string>visible</string></value><unexpected/></data></array>",
            "<array><data>omitted transfer<value><string>visible</string></value></data></array>"
        )) {
            assertThrows("Must refuse malformed inventory: $array", IllegalArgumentException::class.java) {
                XmlRpcCodec.response(response("<value>$array</value>"))
            }
        }
    }

    @Test fun refusesAmbiguousResponseEnvelopesInsteadOfPickingFirstResult() {
        val param = "<param><value><array><data/></array></value></param>"
        val fault = "<fault><value><struct>" +
            "<member><name>faultCode</name><value><int>-501</int></value></member>" +
            "<member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>" +
            "</struct></value></fault>"
        for (content in listOf(
            "<params>$param$param</params>",
            "<params>$param</params><params>$param</params>",
            "<params>$param</params>$fault",
            "$fault$fault",
            "<params>$param<unexpected/></params>",
            "<params><param><value/><value/></param></params>",
            "<params>$param</params>unexpected text"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                XmlRpcCodec.response("<methodResponse>$content</methodResponse>")
            }
        }
    }

    @Test fun refusesMultipleTypesMixedContentAndNestedScalarMarkup() {
        for (value in listOf(
            "<value><int>0</int><int>1</int></value>",
            "<value>unexpected<string>accepted</string></value>",
            "<value><int><string>1</string></int></value>",
            "<value><string>partial<unexpected> hidden</unexpected></string></value>",
            "<value><base64><string>AAEC</string></base64></value>",
            "<value><nil>not empty</nil></value>"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                XmlRpcCodec.response(response(value))
            }
        }
    }

    @Test fun refusesMalformedAndDuplicateStructMembersIncludingFaultIdentity() {
        val member = "<member><name>faultCode</name><value><int>-501</int></value></member>"
        for (members in listOf(
            "$member$member",
            "$member<unexpected/>",
            "<member><name>key</name><name>other</name><value/></member>",
            "<member><name>key</name><value/><value/></member>",
            "<member><name>key<unexpected/></name><value/></member>"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                XmlRpcCodec.response(response("<value><struct>$members</struct></value>"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            XmlRpcCodec.response("<methodResponse><fault><value><struct>$member$member" +
                "</struct></value></fault></methodResponse>")
        }
    }

    @Test fun malformedFaultsCannotAuthorizeMissingTorrentOrMethodFallback() {
        val code = "<member><name>faultCode</name><value><int>-501</int></value></member>"
        val message = "<member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>"
        for (value in listOf(
            "<struct>${code.replace("<int>-501</int>", "<string>-501</string>")}$message</struct>",
            "<struct>$code</struct>",
            "<struct>$message</struct>",
            "<string>Could not find info-hash.</string>"
        )) {
            val failure = assertThrows(Exception::class.java) {
                XmlRpcCodec.response("<methodResponse><fault><value>$value</value></fault></methodResponse>")
            }
            assertTrue(failure is IllegalStateException)
            assertFalse("Malformed replies must not become an authoritative RPC fault", failure is RpcFault)
        }
    }

    @Test fun validEmptyValuesWhitespaceCommentsAndImplicitStringsRemainSupported() {
        assertEquals(emptyList<Any>(), XmlRpcCodec.response(response("<value><array><data/></array></value>")))
        assertEquals(emptyMap<String, Any>(), XmlRpcCodec.response(response("<value><struct/></value>")))
        assertEquals("", XmlRpcCodec.response(response("<value/>")))
        assertEquals("  plain & text  ", XmlRpcCodec.response(response("<value>  plain &amp; text  </value>")))
        assertEquals("left right", XmlRpcCodec.response(response("<value><string>left<![CDATA[ right]]></string></value>")))
        assertNull(XmlRpcCodec.response(response("<value><nil/></value>")))
        assertEquals(listOf(7L), XmlRpcCodec.response(response(
            "<value> \n<!-- type --><array>\n<data><!-- entry --><value><int>7</int></value>\n</data></array></value>"
        )))
    }

}
