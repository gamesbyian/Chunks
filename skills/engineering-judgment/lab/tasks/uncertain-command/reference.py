"""Reference: a side-effect attempt has at most one automatic write."""
def submit(ledger, transaction_id, amount):
    try:
        ledger.write(transaction_id, amount)
    except TimeoutError:
        pass
    try:
        observed = ledger.read(transaction_id)
    except (TimeoutError, ConnectionError):
        return "UNCERTAIN"
    return "VERIFIED" if observed == amount else "UNCERTAIN"
