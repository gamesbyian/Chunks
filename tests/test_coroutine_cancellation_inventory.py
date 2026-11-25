"""Lexical cancellation inventory controls, not a Kotlin compiler or semantic proof."""
import unittest

from tools.check_coroutine_cancellation import candidates, review_differences

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

    def test_changed_literal_is_a_new_identity_and_is_redacted(self):
        first = candidates('suspend fun f() = runCatching { read("one") }', "example.kt")[0]
        second = candidates('suspend fun f() = runCatching { read("two") }', "example.kt")[0]
        self.assertNotEqual(first["fingerprint"], second["fingerprint"])
        self.assertNotIn('"one"', first["excerpt"])

    def test_review_counts_reject_new_and_stale_entries(self):
        row = candidates('suspend fun f() = runCatching { read() }', "example.kt")[0]
        review = {key: row[key] for key in ("path", "kind", "fingerprint")}
        review.update(count=1, reason="Reviewed synchronous parser boundary")
        self.assertEqual([], review_differences([row], [review]))
        self.assertEqual(2, review_differences([row, row], [review])[0]["actual"])
        self.assertEqual(0, review_differences([], [review])[0]["actual"])
        with self.assertRaises(ValueError):
            review_differences([row], [review, review])
        with self.assertRaises(ValueError):
            review_differences([row], [{**review, "reason": ""}])

    def test_committed_reviews_are_only_reviewed_safe_dispositions(self):
        import json
        from pathlib import Path
        reviews = json.loads((Path(__file__).resolve().parent.parent /
                              "tools/coroutine_cancellation_reviews.json").read_text(encoding="utf-8"))
        self.assertTrue(reviews)
        allowed = {"SAFE-NONSUSPEND", "SAFE-RETHROWS", "SAFE-TERMINAL"}
        for review in reviews:
            # A confirmed defect is fixed in source, never parked in the review file.
            self.assertIn(review["disposition"], allowed, review)
            self.assertGreaterEqual(len(review["reason"].strip()), 20, review)
            self.assertTrue(review["path"].startswith("app/src/main/"), review)


if __name__ == "__main__":
    unittest.main()
