# Third-Party Notices

1cdx dirilis di bawah GPL-3.0 dan memakai komponen pihak ketiga berikut.

| Komponen | Lisensi | Sumber |
|---|---|---|
| sora-editor (`editor`, `language-textmate`) | LGPL-2.1 | https://github.com/Rosemoe/sora-editor |
| Eclipse tm4e (dibundel lewat sora-editor) | EPL-2.0 | https://github.com/eclipse/tm4e |
| AndroidX, Kotlin, kotlinx.coroutines | Apache-2.0 | https://developer.android.com/jetpack/androidx |

## Grammar & tema TextMate (`app/src/main/assets/textmate/`)

- Grammar Go, Python, Rust, JavaScript, TypeScript, CSS, HTML, JSON, SQL, Shell Script, Java, Markdown, XML,
  beserta `language-configuration.json`-nya, diambil dari VS Code **1.95.3** (`extensions/*/syntaxes`, lisensi MIT,
  https://github.com/microsoft/vscode). Asal upstream dan lisensi tiap grammar tercantum di file
  `extensions/<bahasa>/cgmanifest.json` pada repo VS Code.
- Tema `dark_plus.json` adalah gabungan `dark_vs.json` + `dark_plus.json` dari VS Code 1.95.3 (MIT), dengan beberapa
  warna editor ditambahkan.
- Grammar Kotlin diambil dari aset demo sora-editor 0.23.6.

Aset ini dibuat ulang dengan skrip `tools/fetch_textmate.py`.
