"""Fetch VS Code TextMate grammars + Dark+ theme into the app assets.

Usage: python3 tools/fetch_textmate.py app/src/main/assets/textmate
"""
import json, os, re, sys, urllib.request

VSC = "1.95.3"
SORA = "0.23.6"
OUT = sys.argv[1]  # .../app/src/main/assets/textmate

def raw(repo, ref, path):
    url = f"https://raw.githubusercontent.com/{repo}/{ref}/{path}"
    with urllib.request.urlopen(url) as r:
        return r.read().decode("utf-8")

def strip_jsonc(text):
    out, i, n, in_str = [], 0, len(text), False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\":
                out.append(text[i + 1]); i += 2; continue
            if c == '"':
                in_str = False
            i += 1; continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if text.startswith("//", i):
            while i < n and text[i] != "\n": i += 1
            continue
        if text.startswith("/*", i):
            i = text.index("*/", i) + 2; continue
        out.append(c); i += 1
    s = "".join(out)
    return re.sub(r",(\s*[}\]])", r"\1", s)

# (id, display name, vscode ext dir, grammar file, language-config file, embedded)
LANGS = [
    ("go", "Go", "go", "go.tmLanguage.json", "language-configuration.json", None),
    ("python", "Python", "python", "MagicPython.tmLanguage.json", "language-configuration.json", None),
    ("rust", "Rust", "rust", "rust.tmLanguage.json", "language-configuration.json", None),
    ("javascript", "JavaScript", "javascript", "JavaScript.tmLanguage.json", "javascript-language-configuration.json", None),
    ("typescript", "TypeScript", "typescript-basics", "TypeScript.tmLanguage.json", "language-configuration.json", None),
    ("css", "CSS", "css", "css.tmLanguage.json", "language-configuration.json", None),
    ("html", "HTML", "html", "html.tmLanguage.json", "language-configuration.json",
        {"source.js": "javascript", "source.css": "css"}),
    ("json", "JSON", "json", "JSON.tmLanguage.json", "language-configuration.json", None),
    ("sql", "SQL", "sql", "sql.tmLanguage.json", "language-configuration.json", None),
    ("shellscript", "Shell Script", "shellscript", "shell-unix-bash.tmLanguage.json", "language-configuration.json", None),
    ("java", "Java", "java", "java.tmLanguage.json", "language-configuration.json", None),
    ("markdown", "Markdown", "markdown-basics", "markdown.tmLanguage.json", "language-configuration.json",
        {"source.js": "javascript", "source.css": "css", "source.python": "python", "source.go": "go",
         "source.rust": "rust", "source.ts": "typescript", "source.json": "json", "source.shell": "shellscript",
         "source.sql": "sql", "source.java": "java", "text.html.basic": "html"}),
]

languages = []
for lid, display, ext, gfile, cfile, embedded in LANGS:
    d = os.path.join(OUT, lid); os.makedirs(d, exist_ok=True)
    grammar = json.loads(raw("microsoft/vscode", VSC, f"extensions/{ext}/syntaxes/{gfile}"))
    with open(os.path.join(d, gfile), "w") as f: json.dump(grammar, f, separators=(",", ":"))
    conf = json.loads(strip_jsonc(raw("microsoft/vscode", VSC, f"extensions/{ext}/{cfile}")))
    with open(os.path.join(d, "language-configuration.json"), "w") as f: json.dump(conf, f, indent=1)
    entry = {"name": lid, "scopeName": grammar["scopeName"], "grammar": f"textmate/{lid}/{gfile}",
             "languageConfiguration": f"textmate/{lid}/language-configuration.json"}
    if embedded: entry["embeddedLanguages"] = embedded
    languages.append(entry)
    print(f"{lid:12} {grammar['scopeName']}")

# Kotlin (VS Code has no built-in Kotlin grammar) from the sora-editor demo assets
d = os.path.join(OUT, "kotlin"); os.makedirs(d, exist_ok=True)
with open(os.path.join(d, "Kotlin.tmLanguage"), "w") as f:
    f.write(raw("Rosemoe/sora-editor", SORA, "app/src/main/assets/textmate/kotlin/syntaxes/Kotlin.tmLanguage"))
conf = json.loads(strip_jsonc(raw("Rosemoe/sora-editor", SORA, "app/src/main/assets/textmate/kotlin/language-configuration.json")))
with open(os.path.join(d, "language-configuration.json"), "w") as f: json.dump(conf, f, indent=1)
languages.append({"name": "kotlin", "scopeName": "source.kotlin", "grammar": "textmate/kotlin/Kotlin.tmLanguage",
                  "languageConfiguration": "textmate/kotlin/language-configuration.json"})
print("kotlin       source.kotlin")

# XML grammar (no language-configuration upstream)
d = os.path.join(OUT, "xml"); os.makedirs(d, exist_ok=True)
g = json.loads(raw("microsoft/vscode", VSC, "extensions/xml/syntaxes/xml.tmLanguage.json"))
with open(os.path.join(d, "xml.tmLanguage.json"), "w") as f: json.dump(g, f, separators=(",", ":"))
languages.append({"name": "xml", "scopeName": g["scopeName"], "grammar": "textmate/xml/xml.tmLanguage.json"})
print(f"xml          {g['scopeName']}")

with open(os.path.join(OUT, "languages.json"), "w") as f:
    json.dump({"languages": languages}, f, indent=2)

# Dark+ theme = dark_vs + dark_plus merged (sora's ThemeModel does not follow "include")
vs = json.loads(strip_jsonc(raw("microsoft/vscode", VSC, "extensions/theme-defaults/themes/dark_vs.json")))
plus = json.loads(strip_jsonc(raw("microsoft/vscode", VSC, "extensions/theme-defaults/themes/dark_plus.json")))
colors = {**vs.get("colors", {}), **plus.get("colors", {})}
colors.update({
    "editor.background": "#1E1E1E",
    "editor.foreground": "#D4D4D4",
    "editorLineNumber.foreground": "#858585",
    "editorLineNumber.activeForeground": "#C6C6C6",
    "editorCursor.foreground": "#AEAFAD",
    "editor.lineHighlightBackground": "#2A2D2E",
    "editor.selectionBackground": "#264F78",
    "editorIndentGuide.background": "#404040",
    "editorIndentGuide.activeBackground": "#707070",
    "editorWhitespace.foreground": "#3B3B3B",
})
theme = {"name": "Dark+ (1cdx)", "type": "dark", "colors": colors,
         "tokenColors": vs.get("tokenColors", []) + plus.get("tokenColors", [])}
with open(os.path.join(OUT, "dark_plus.json"), "w") as f: json.dump(theme, f, indent=1)
print("theme dark_plus.json:", len(theme["tokenColors"]), "rules")
