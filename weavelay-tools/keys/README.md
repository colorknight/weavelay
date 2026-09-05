# Vendor licence keys — NEVER ship to customers

签发步骤见同目录 [授权签发说明.md](授权签发说明.md)。默认按 **365 天** 生成。

Generate keys (from repo root):

```bash
mvn -q -pl weavelay-tools -am package
mvn -q -pl weavelay-tools exec:java -Dexec.args="keygen --dir weavelay-tools/keys"
```

Keep `private.ed25519.b64` only on the vendor machine. Customer packages should only include `weavelay-app` (+ OCR models / JRE), never `weavelay-tools`.
