import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PATHS = ["src", "build.gradle.kts", "settings.gradle.kts"]
LANGS = {".java": "java", ".kts": "java", ".js": "js", ".css": "css", ".html": "html"}
SKIP_DIRS = {"build", ".gradle", "node_modules", "data"}
KEEP = re.compile(r"license|copyright|apache-2\.0|\bmit\b|\bofl\b|spdx", re.I)
MARK = "\x00"
REGEX_BEFORE = set("(,=:[!&|?{};+-*%<>~^")
REGEX_WORDS = {"return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw", "yield", "await"}


def comment(text):
    return text if KEEP.search(text) else MARK


def read_string(src, i, quote):
    j = i + 1
    while j < len(src) and src[j] != quote and src[j] != "\n":
        j += 2 if src[j] == "\\" else 1
    return j + 1


def read_regex(src, i):
    j, in_class = i + 1, False
    while j < len(src) and src[j] != "\n":
        c = src[j]
        if c == "\\":
            j += 2
            continue
        if c == "[":
            in_class = True
        elif c == "]":
            in_class = False
        elif c == "/" and not in_class:
            j += 1
            while j < len(src) and src[j].isalpha():
                j += 1
            return j
        j += 1
    return j


def strip_code(src, lang):
    out, i, n = [], 0, len(src)
    braces, in_template = [], False
    last, word = "", ""

    def emit(text):
        nonlocal last, word
        out.append(text)
        stripped = text.rstrip()
        if stripped:
            last = stripped[-1]
            m = re.search(r"[A-Za-z_$][\w$]*$", stripped)
            word = m.group(0) if m else ""

    while i < n:
        c = src[i]
        if in_template:
            if c == "\\":
                out.append(src[i:i + 2])
                i += 2
            elif c == "`":
                in_template = False
                emit(c)
                i += 1
            elif src.startswith("${", i):
                in_template = False
                braces.append(0)
                emit("${")
                i += 2
            else:
                out.append(c)
                i += 1
            continue

        if src.startswith("/*", i):
            end = src.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append(comment(src[i:end]))
            i = end
        elif src.startswith("//", i) and lang != "css":
            end = src.find("\n", i)
            end = n if end < 0 else end
            out.append(comment(src[i:end]))
            i = end
        elif lang == "java" and src.startswith('"""', i):
            end = src.find('"""', i + 3)
            end = n if end < 0 else end + 3
            emit(src[i:end])
            i = end
        elif c in "\"'":
            end = read_string(src, i, c)
            emit(src[i:end])
            i = end
        elif lang == "js" and c == "`":
            in_template = True
            out.append(c)
            i += 1
        elif lang == "js" and c == "/" and (last == "" or last in REGEX_BEFORE or word in REGEX_WORDS) and not (last.isalnum() and word not in REGEX_WORDS):
            end = read_regex(src, i)
            emit(src[i:end])
            i = end
        elif lang == "js" and c == "{" and braces:
            braces[-1] += 1
            emit(c)
            i += 1
        elif lang == "js" and c == "}" and braces:
            if braces[-1] == 0:
                braces.pop()
                in_template = True
                out.append(c)
            else:
                braces[-1] -= 1
                emit(c)
            i += 1
        else:
            if c.isspace():
                out.append(c)
            else:
                emit(c)
            i += 1
    return "".join(out)


def strip_html(src):
    out, i = [], 0
    pattern = re.compile(r"<!--|<script\b|<style\b", re.I)
    while True:
        m = pattern.search(src, i)
        if not m:
            out.append(src[i:])
            break
        out.append(src[i:m.start()])
        token = m.group(0).lower()
        if token == "<!--":
            end = src.find("-->", m.start())
            end = len(src) if end < 0 else end + 3
            out.append(comment(src[m.start():end]))
            i = end
            continue
        tag = "script" if token == "<script" else "style"
        open_end = src.find(">", m.start()) + 1
        close = src.lower().find(f"</{tag}>", open_end)
        close = len(src) if close < 0 else close
        out.append(src[m.start():open_end])
        out.append(strip_code(src[open_end:close], "js" if tag == "script" else "css"))
        i = close
    return "".join(out)


def tidy(text):
    lines = []
    for line in text.split("\n"):
        if MARK in line:
            rest = line.replace(MARK, "")
            if not rest.strip():
                continue
            line = rest.rstrip()
        lines.append(line)
    text = "\n".join(lines)
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"\{\n\n(\s*\})", r"{\n\1", text)
    return text.lstrip("\n")


def process(path):
    raw = path.read_bytes().decode("utf-8")
    newline = "\r\n" if "\r\n" in raw else "\n"
    src = raw.replace("\r\n", "\n")
    lang = LANGS[path.suffix]
    stripped = strip_html(src) if lang == "html" else strip_code(src, lang)
    if MARK not in stripped:
        return None
    result = tidy(stripped)
    if src.endswith("\n") and not result.endswith("\n"):
        result += "\n"
    return result.replace("\n", newline)


def files(paths):
    for p in paths:
        p = (ROOT / p) if not Path(p).is_absolute() else Path(p)
        if p.is_file() and p.suffix in LANGS:
            yield p
        elif p.is_dir():
            for f in sorted(p.rglob("*")):
                if f.is_file() and f.suffix in LANGS and not SKIP_DIRS.intersection(f.relative_to(ROOT).parts):
                    yield f


def main():
    parser = argparse.ArgumentParser(description="Remove comments from source files (license/attribution comments are kept).")
    parser.add_argument("paths", nargs="*", default=DEFAULT_PATHS, help="files or directories, relative to the project root")
    parser.add_argument("--check", action="store_true", help="only list files that still contain comments")
    args = parser.parse_args()

    changed = 0
    for f in files(args.paths):
        result = process(f)
        if result is None:
            continue
        changed += 1
        print(("has comments: " if args.check else "cleaned: ") + str(f.relative_to(ROOT)))
        if not args.check:
            f.write_bytes(result.encode("utf-8"))
    print(f"{changed} file(s) {'with comments' if args.check else 'cleaned'}")
    return 1 if args.check and changed else 0


if __name__ == "__main__":
    sys.exit(main())
