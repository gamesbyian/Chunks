Fixture generated for Chunks with the independent RARLAB RAR 7.23 Linux compressor.
Only the two archive volumes are included. Their payload is generated from SHA-256:
  payload = b''.join(hashlib.sha256(str(i).encode()).digest() for i in range(32))
  pathlib.Path("known.bin").write_bytes(payload + payload[:24])
  touch -d '2026-01-01T00:00:00Z' known.bin
  rar a -ma5 -m3 -s -v600b -ep -idq Verified.rar known.bin

SHA-256 of Verified.part1.rar:
4cc78dae7289d222afae0c28a1e186c59dba7298cf49e7cfa25ad57230a054f7
SHA-256 of Verified.part2.rar:
a5cd28ebb8b985e7c43f99d510e7cdf244b813f2b67e4702761e016eee8c216c
SHA-256 of known.bin independently read with UnRAR 7.23:
05abc6df769ec4637bcb82ddc1b5514d910d62502b40c1336aa15436405e2696

This is a genuine compressed multipart input. The Android test observes
headers through Local Details and does not decode or verify the payload.
