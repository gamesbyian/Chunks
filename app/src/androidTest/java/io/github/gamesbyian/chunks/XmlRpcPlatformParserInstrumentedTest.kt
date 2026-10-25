package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.XmlRpcCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's actual platform DocumentBuilderFactory, not a desktop JVM. */
@RunWith(AndroidJUnit4::class)
class XmlRpcPlatformParserInstrumentedTest {
    private val reply = """<?xml version="1.0"?>
        <methodResponse><params><param><value><string>0.9.8</string></value>
        </param></params></methodResponse>""".trimIndent()

    @Test fun platformParserCanReadOrdinaryProtectedRpcResponse() {
        assertEquals("0.9.8", XmlRpcCodec.response(reply))
        assertEquals(listOf(1L, 2L), XmlRpcCodec.response(
            "<methodResponse><params><param><value><array><data>" +
                "<value><i8>1</i8></value><value><i8>2</i8></value>" +
                "</data></array></value></param></params></methodResponse>"))
    }

    @Test fun doctypeAndEntityInputRejectedBeforeAnyParserImplementation() {
        val malicious = """<?xml version="1.0"?>
            <!DOCTYPE methodResponse [<!ENTITY hostile SYSTEM "file:///etc/passwd">]>
            <methodResponse><params><param><value><string>&hostile;</string></value>
            </param></params></methodResponse>""".trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            XmlRpcCodec.response(malicious)
        }
        assertThrows(IllegalArgumentException::class.java) {
            XmlRpcCodec.response(reply + "<!ENTITY external>")
        }
    }
    @Test fun nativeSaxPreflightRejectsDeepHostileStructureWithoutBreakingNormalReplies() {
        val nested = buildString {
            repeat(60) { append("<value><array><data>") }
            append("<value><string>hidden</string></value>")
            repeat(60) { append("</data></array></value>") }
        }
        val deep = "<methodResponse><params><param>" + nested +
            "</param></params></methodResponse>"
        assertThrows(IllegalArgumentException::class.java) {
            XmlRpcCodec.response(deep)
        }
        assertEquals("0.9.8", XmlRpcCodec.response(reply))
    }

}
