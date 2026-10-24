package io.github.gamesbyian.chunks.rpc

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Strict, bounded-use XML-RPC codec. No DTD/entity expansion, no dynamic class decoding.
 * Does not retry or issue RPC commands; transport controls the maximum response size.
 */
object XmlRpcCodec {
    private val validMethod = Regex("[A-Za-z0-9_.]+")

    fun request(method: String, parameters: List<Any> = emptyList()): String {
        require(validMethod.matches(method)) { "Invalid RPC method name" }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<methodCall><methodName>").append(method).append("</methodName><params>")
            for (parameter in parameters) {
                append("<param>").append(encodeValue(parameter)).append("</param>")
            }
            append("</params></methodCall>")
        }
    }

    private fun encodeValue(value: Any): String = when (value) {
        is ByteArray -> {
            require(value.size <= 2_097_152) { "Binary RPC payload exceeds 2 MiB" }
            "<value><base64>" + java.util.Base64.getEncoder().encodeToString(value) + "</base64></value>"
        }
        is String -> "<value><string>${escape(value)}</string></value>"
        is Int, is Long -> "<value><i8>$value</i8></value>"
        is Boolean -> "<value><boolean>${if (value) "1" else "0"}</boolean></value>"
        is List<*> -> "<value><array><data>" +
            value.joinToString("") { encodeValue(requireNotNull(it)) } +
            "</data></array></value>"
        else -> throw IllegalArgumentException("Unsupported XML-RPC value type")
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> {
                require(c == '\n' || c == '\t' || c == '\r' || c.code >= 0x20) {
                    "Control character in RPC parameter"
                }
                append(c)
            }
        }
    }

    fun response(xml: String): Any? {
        require(xml.length <= 4_194_304) { "RPC response too large" }
        // Android's DOM implementation does not universally support Xerces feature
        // flags. Reject DTD syntax *before* parsing; entities requiring DTDs
        // can never be supplied to a parser on either Android or the JVM.
        require(!xml.contains("<!DOCTYPE") && !xml.contains("<!ENTITY")) {
            "XML document declarations and entities are not permitted"
        }
        val factory = DocumentBuilderFactory.newInstance()
        // Best-effort defense in depth. A missing vendor-specific feature does
        // not justify disabling the mandatory lexical DTD rejection above.
        for ((name, enabled) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false
        )) {
            try { factory.setFeature(name, enabled) }
            catch (_: javax.xml.parsers.ParserConfigurationException) { /* Android parser may omit flag */ }
        }
        // Android's platform DocumentBuilderFactory can throw
        // UnsupportedOperationException even when setting XInclude to false.
        // This must never prevent an ordinary authenticated XML-RPC reply from
        // being parsed. Lexical DOCTYPE/ENTITY rejection above is mandatory
        // regardless of which optional parser controls this implementation has.
        try { factory.isXIncludeAware = false }
        catch (_: UnsupportedOperationException) { /* unsupported on some Android parsers */ }
        try { factory.isExpandEntityReferences = false }
        catch (_: UnsupportedOperationException) { /* DTD/entity input is already rejected */ }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = doc.documentElement
        require(root.tagName == "methodResponse") { "Invalid XML-RPC response" }
        val fault = child(root, "fault")
        if (fault != null) {
            val decoded = child(fault, "value")?.let(::readValue)
            val map = decoded as? Map<*, *>
            throw RpcFault(map?.get("faultCode")?.toString() ?: "unknown",
                map?.get("faultString")?.toString() ?: "rTorrent returned an RPC fault")
        }
        val params = child(root, "params") ?: error("Missing RPC parameters")
        val param = child(params, "param") ?: error("Missing RPC parameter")
        return readValue(child(param, "value") ?: error("Missing RPC value"))
    }

    private fun readValue(element: Element): Any? {
        val typed = children(element).firstOrNull() ?: return element.textContent.orEmpty()
        return when (typed.tagName) {
            "string", "dateTime.iso8601" -> typed.textContent.orEmpty()
            "base64" -> {
                val encoded = typed.textContent.filterNot { it.isWhitespace() }
                require(encoded.length <= 2_796_204) { "Binary RPC response exceeds 2 MiB" }
                val bytes = java.util.Base64.getDecoder().decode(encoded)
                require(bytes.size <= 2_097_152) { "Binary RPC response exceeds 2 MiB" }
                bytes
            }
            "int", "i4", "i8" -> typed.textContent.trim().toLong()
            "boolean" -> when (typed.textContent.trim()) {
                "1" -> true
                "0" -> false
                else -> error("Invalid XML-RPC boolean")
            }
            "double" -> typed.textContent.trim().toDouble()
            "nil" -> null
            "array" -> child(typed, "data")?.let { data ->
                children(data).filter { it.tagName == "value" }.map(::readValue)
            } ?: emptyList<Any>()
            "struct" -> children(typed).filter { it.tagName == "member" }.associate { member ->
                val name = child(member, "name")?.textContent ?: error("Invalid struct member")
                name to readValue(child(member, "value") ?: error("Missing member value"))
            }
            else -> error("Unsupported XML-RPC response type: ${typed.tagName}")
        }
    }

    private fun child(parent: Element, tag: String): Element? =
        children(parent).firstOrNull { it.tagName == tag }

    private fun children(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.nodeType == Node.ELEMENT_NODE) out.add(node as Element)
        }
        return out
    }
}

class RpcFault(val code: String, message: String) : Exception(message)
