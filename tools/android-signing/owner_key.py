"""Owner-only PKCS12 helper. Never run to generate a user's key in Codex/CI."""
import base64
from datetime import datetime, timedelta, timezone
import json
import re
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID


def password_bytes(password):
    if not isinstance(password, str) or not 24 <= len(password) <= 128 or any(not 33 <= ord(c) <= 126 for c in password):
        raise ValueError('Utilisez un mot de passe NordPass aléatoire de 32 caractères ASCII.')
    return password.encode('ascii')


def create_key(password):
    secret = password_bytes(password)
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Moli OS Beta')])
    now = datetime.now(timezone.utc)
    certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                   .public_key(key.public_key()).serial_number(x509.random_serial_number())
                   .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=10000))
                   .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                   .sign(key, hashes.SHA256()))
    blob = pkcs12.serialize_key_and_certificates(b'moli-beta', key, certificate, None, serialization.BestAvailableEncryption(secret))
    return blob, certificate.fingerprint(hashes.SHA256()).hex()


def recovery_bundle(blob, password, expected_fingerprint, *, nordpass_confirmed):
    if nordpass_confirmed is not True:
        raise ValueError('Retéléchargez et vérifiez le fichier depuis votre entrée NordPass.')
    if not re.fullmatch('[a-f0-9]{64}', expected_fingerprint) or not 1000 <= len(blob) <= 20000:
        raise ValueError('Fichier ou empreinte invalide.')
    try:
        key, certificate, chain = pkcs12.load_key_and_certificates(blob, password_bytes(password))
        if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 3072 or certificate is None or chain:
            raise ValueError()
        if key.public_key().public_numbers() != certificate.public_key().public_numbers():
            raise ValueError()
        if certificate.fingerprint(hashes.SHA256()).hex() != expected_fingerprint:
            raise ValueError()
    except Exception:
        raise ValueError('Le fichier, le mot de passe ou l’empreinte ne correspond pas.') from None
    return json.dumps({'format': 1, 'p12': base64.b64encode(blob).decode('ascii'), 'password': password,
                       'certificate_sha256': expected_fingerprint, 'nordpass_confirmed': True}, separators=(',', ':'))
