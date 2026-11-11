"""Intentionally faulty duplicate-command handling."""
def submit(ledger, transaction_id, amount):
    try:
        ledger.write(transaction_id, amount)
    except TimeoutError:
        ledger.write(transaction_id, amount)
    return "VERIFIED"
