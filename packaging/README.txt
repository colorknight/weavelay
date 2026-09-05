WeaveLay portable package

Run: double-click WeaveLay.bat

Licence: Settings -> Licence -> copy machine fingerprint to vendor -> import .weavelaylic

Notes:
- Bundled Java runtime + models (no JDK install needed):
  models\ppocrv6     text OCR (PP-OCRv6 ONNX)
  models\pp-formula  formula OCR (PP-FormulaNet ONNX: backbone + head_fixed + tokenizer)
- Does NOT include vendor signing tools or private keys
- Data dir default: %LOCALAPPDATA%\WeaveLay
