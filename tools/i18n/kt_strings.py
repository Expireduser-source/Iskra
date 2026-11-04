#!/usr/bin/env python3
"""
Russian → Russian + English for Kotlin sources: every user-visible Russian string becomes tr("рус", "eng").

  python3 -I tools/i18n/kt_strings.py list  <File.kt>              # numbered Russian literals (chains joined by +)
  python3 -I tools/i18n/kt_strings.py apply <File.kt> <map.json>   # {"3": "English text", "5": "-"}; "-" = leave as is

A literal chain is a run of plain string literals joined with "+", so a sentence split over lines is one item.
The English text is inserted verbatim between quotes (it may contain $templates and ${...}).
Raw strings (triple quotes) are listed as RAW and never changed automatically.
"""
import json
import re
import sys

CYR = re.compile(r"[А-Яа-яЁё]")


def scan(src):
    """Top-level string literals: list of (start, end, raw?) offsets, skipping comments and char literals."""
    out, i, n = [], 0, len(src)

    def skip_string(j):
        # src[j] is the opening quote of a plain string; returns index after the closing quote.
        j += 1
        while j < n:
            c = src[j]
            if c == "\\":
                j += 2
                continue
            if c == '"':
                return j + 1
            if c == "$" and j + 1 < n and src[j + 1] == "{":
                j = skip_code(j + 2, "}")
                continue
            j += 1
        return j

    def skip_raw(j):
        j += 3
        while j < n:
            if src.startswith('"""', j):
                k = j + 3
                while k < n and src[k] == '"':
                    k += 1
                return k
            if src[j] == "$" and j + 1 < n and src[j + 1] == "{":
                j = skip_code(j + 2, "}")
                continue
            j += 1
        return j

    def skip_code(j, closer):
        depth = 0
        while j < n:
            c = src[j]
            if src.startswith("//", j):
                j = src.find("\n", j)
                j = n if j < 0 else j
                continue
            if src.startswith("/*", j):
                j = src.find("*/", j + 2)
                j = n if j < 0 else j + 2
                continue
            if src.startswith('"""', j):
                j = skip_raw(j)
                continue
            if c == '"':
                j = skip_string(j)
                continue
            if c == "'":
                m = re.match(r"'(\\.|[^'\\])+'", src[j:])
                j += len(m.group(0)) if m else 1
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                if depth == 0:
                    return j + 1
                depth -= 1
            j += 1
        return j

    while i < n:
        c = src[i]
        if src.startswith("//", i):
            i = src.find("\n", i)
            i = n if i < 0 else i
            continue
        if src.startswith("/*", i):
            i = src.find("*/", i + 2)
            i = n if i < 0 else i + 2
            continue
        if src.startswith('"""', i):
            e = skip_raw(i)
            out.append((i, e, True))
            i = e
            continue
        if c == '"':
            e = skip_string(i)
            out.append((i, e, False))
            i = e
            continue
        if c == "'":
            m = re.match(r"'(\\.|[^'\\])+'", src[i:])
            i += len(m.group(0)) if m else 1
            continue
        i += 1
    return out


def items(src):
    lits = scan(src)
    res, k = [], 0
    while k < len(lits):
        s, e, raw = lits[k]
        if raw:
            if CYR.search(src[s:e]):
                res.append({"start": s, "end": e, "raw": True})
            k += 1
            continue
        # Join "a" + "b" + ... (only whitespace and + between plain literals).
        j = k
        while j + 1 < len(lits) and not lits[j + 1][2] and re.fullmatch(r"\s*\+\s*", src[lits[j][1]:lits[j + 1][0]]):
            j += 1
        s2, e2 = lits[k][0], lits[j][1]
        if CYR.search(src[s2:e2]):
            res.append({"start": s2, "end": e2, "raw": False})
        k = j + 1
    for idx, it in enumerate(res, 1):
        it["id"] = str(idx)
        it["line"] = src.count("\n", 0, it["start"]) + 1
    return res


def flags(src, it):
    line_start = src.rfind("\n", 0, it["start"]) + 1
    line_end = src.find("\n", it["end"])
    line = src[line_start: line_end if line_end >= 0 else len(src)]
    after = src[it["end"]: it["end"] + 4]
    f = []
    if "const val" in line:
        f.append("CONST")
    if re.match(r"\s*(private |internal |override )*val \w+(\s*:\s*[\w<>?, ]+)?\s*=", line):
        f.append("VAL")
    if after.lstrip().startswith("->") or re.search(r"\bin (set|list)Of\(|setOf\(|mapOf\(|to \d|Regex\(|startsWith\(|contains\(|endsWith\(|==", line):
        f.append("INPUT?")
    if "Diag." in line:
        f.append("LOG")
    return f


def cmd_list(path):
    src = open(path, encoding="utf-8").read()
    for it in items(src):
        text = re.sub(r"\s*\n\s*", " ", src[it["start"]:it["end"]])
        kind = "RAW " if it["raw"] else ""
        fl = " ".join(flags(src, it))
        print(f'{it["id"]}|L{it["line"]}|{kind}{fl}| {text}')


def add_import(src):
    """Adds `import ru.iskra.core.i18n.tr` to the import block (or right after the package line)."""
    if re.search(r"^import ru\.iskra\.core\.i18n\.tr$", src, re.M):
        return src
    m = re.search(r"^import [^\n]+\n", src, re.M)
    if m:
        return src[:m.start()] + "import ru.iskra.core.i18n.tr\n" + src[m.start():]
    m = re.search(r"^package [^\n]+\n", src, re.M)
    return src[:m.end()] + "\nimport ru.iskra.core.i18n.tr\n" + src[m.end():]


def cmd_apply(path, mapping_path, func="tr"):
    src = open(path, encoding="utf-8").read()
    mapping = json.load(open(mapping_path, encoding="utf-8"))
    its = items(src)
    unknown = set(mapping) - {it["id"] for it in its}
    if unknown:
        sys.exit(f"unknown ids: {sorted(unknown, key=int)}")
    changed = 0
    for it in reversed(its):
        en = mapping.get(it["id"])
        if en is None or en == "-" or it["raw"]:
            continue
        orig = src[it["start"]:it["end"]]
        en_lit = en if en.startswith('"') else '"' + en + '"'
        src = src[:it["start"]] + f"{func}({orig}, {en_lit})" + src[it["end"]:]
        changed += 1
    missing = [it["id"] for it in its if it["id"] not in mapping and not it["raw"]]
    if changed and func == "tr":
        src = add_import(src)
    open(path, "w", encoding="utf-8").write(src)
    print(f"{path}: {changed} translated" + (f", not in map: {','.join(missing)}" if missing else ""))


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "list":
        for p in sys.argv[2:]:
            print(f"== {p}")
            cmd_list(p)
    elif len(sys.argv) >= 3 and sys.argv[1] == "import":
        for p in sys.argv[2:]:
            src = open(p, encoding="utf-8").read()
            open(p, "w", encoding="utf-8").write(add_import(src))
    elif len(sys.argv) == 4 and sys.argv[1] == "apply":
        cmd_apply(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
