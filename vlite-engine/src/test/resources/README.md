`package-test-signers.p12` contains two deliberately public, disposable test signing
identities (`signer-a`, `signer-b`). Its store and key password is `test-only`.
It was generated with JDK keytool for these offline tests. Never use these keys
to sign distributed applications. No production or user credentials are present.
