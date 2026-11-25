"""Lexical cancellation inventory controls, not a Kotlin compiler or semantic proof."""
import unittest

from tools.check_coroutine_cancellation import candidates

class CancellationInventoryTests(unittest.TestCase):
    def scan(self, source):
        return [row["kind"] for row in candidates(source, "example.kt")]

    def test_suspend_and_bridge_catches(self):
        self.assertEqual(["broad-catch", "runCatching"], self.scan("""
suspend fun operation(): String? = try { fetch() } catch (_: Exception) { null }
fun bridge() = runBlocking { runCatching { read() }.getOrNull() }
"""))

    def test_explicit_rethrows_and_ordinary_sync_function(self):
        self.assertEqual([], self.scan("""
suspend fun operation() {
    try { read() } catch (c: CancellationException) { throw c }
    catch (_: Exception) { null }
    try { read() } catch (e: Throwable) { if (e is CancellationException) throw e; null }
    runCatching { read() }.onFailure { if (it is CancellationException) throw it }
}
fun sync() { runCatching { localParse() } }
"""))

    def test_nested_comments_raw_and_interpolated_strings_are_opaque(self):
        self.assertEqual(["broad-catch"], self.scan('''
suspend fun f() {
    /* outer /* catch (_: Exception) {} */ runCatching {} */
    val a = """runCatching { no() }"""
    val b = "a ${foo("inner ${bar()}")} b"
    try { operation() } catch (_: RuntimeException) { null }
}
'''))

    def test_conditional_cancellation_catch_is_not_blanket_exemption(self):
        self.assertEqual(["broad-catch"], self.scan("""
suspend fun f() {
    try { operation() }
    catch (c: CancellationException) { if (ownerCurrent()) throw c }
    catch (_: Exception) { null }
}
"""))

    def test_expression_body_does_not_include_next_declaration(self):
        self.assertEqual([], self.scan("""
suspend fun f() = read()
fun sync() = runCatching { localParse() }
"""))

if __name__ == "__main__":
    unittest.main()
