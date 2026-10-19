# Portable recovery envelope v1 (development)

This format preserves the existing engine data formats; it does not implement a
Linux importer or prove migration compatibility. No Android Keystore key is
needed to decrypt the envelope. The portable Moli key inside it remains secret.

All integers are unsigned, big-endian. Passwords use PBKDF2-HMAC-SHA256, 600,000
iterations, 256-bit output, UTF-8 encoding without Unicode normalization. The UI
accepts 16–1,024 Java UTF-16 code units; prefer random words and retain exact spelling.

The 32-byte header is ASCII `MOLIBAK1` (8), random salt (16), random nonce prefix (8).
The magic fixes all algorithms/parameters; reject unknown versions. Each record:

- plaintext length `L` as 4 bytes, from 0 through 65,536;
- AES-256-GCM ciphertext of length `L` followed by a 16-byte authentication tag;
- nonce = header nonce prefix followed by the 4-byte record index, starting at 0;
- AAD = the complete header, record index (4 bytes), plaintext length (4 bytes).

A mandatory authenticated record with `L=0` ends the stream. Missing/reordered,
corrupted or trailing records/bytes must fail. Bound cumulative decrypted bytes
before allocation (520 MiB), and reject more than 8,321 records including the final
record. The exporter uses full 65,536-byte records except its last data record;
the count cap also bounds overhead from malicious tiny records. Indices cannot wrap. Chunking bounds
AEAD memory use, without relying on provider buffering an entire archive.

Record plaintext concatenates to a UTF-8-named ZIP. First entry `manifest.json`
(max 4 MiB) contains JSON `format: 1`, `engine: "0.1.0"`, `recovery: {master, pin}`
and `files: {relativePath: {size, sha256}}`. Master is the original lowercase
64-hex Moli key, PIN 4–32 digits. Hashes are lowercase SHA-256. Remaining entries
are exactly `data/<relativePath>`, once each; no directory entries. The credentials
are only in the encrypted manifest; no standalone plaintext key file is written.

Limits: 10,000 files, 512 MiB cumulative uncompressed content, nonnegative sizes,
1,024 Java characters per path, at most 13 components. Refuse absolute paths,
empty/dot/dot-dot components, backslashes, NUL, and any `master.key` component.
Exporter refuses symlinks and nonregular files. Importer creates regular files in
an empty private staging tree, checks exact inventory, sizes and hashes, then
consumes/authenticates the complete sealed stream, including its final record.
Wrong password and damaged ciphertext share a generic failure message.

The engine must be fully stopped and flushed; the host serializes maintenance
with all JNI operations. Include every internal data file, including SQLite WAL
sidecars if present after shutdown. There is no live SQLite snapshot on content
URIs. Host keys, Android caches and arbitrary external referenced paths are not
archived. Restore accepts only the same engine version for now; no schema upgrade
is attempted. The Android data-generation selector is omitted from export.

Atomic activation associates the staged data directory with a newly Keystore-wrapped
copy of the recovered portable key/PIN. Existing installations are refused, not
rekeyed or replaced. Archive authentication protects against damage and possession
without the password; it does not establish who authored a backup. A holder of
the password can author configurations/automations: import only a trusted file,
and review before manually starting the restored engine.
