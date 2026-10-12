#!/usr/bin/env python3
"""生成 GitHub Release 的正文（release notes）。

约定：本次版本的更新摘要由发版代理（agent）写在
`.github/release-notes/<tag>.md`，CI 读取后拼接「全部提交」与
Full Changelog 链接作为 Release 正文；摘要文件缺失时降级为仅提交列表。

用法（CI 中由 release.yml 调用）：

    GITHUB_REF_NAME=v1.1.0 \
    GITHUB_REPOSITORY=Frost819/newBV \
    python3 .github/scripts/generate_release_body.py release_body.md

说明：仓库提交多为直接 push（无 PR），GitHub 的 generate_release_notes
只会输出一个 compare 链接，故改为从提交信息生成。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

# 按 conventional commit 前缀分组；未识别的归入「其他」
_LABELS: list[tuple[str, str]] = [
    ("feat", "✨ 新功能"),
    ("fix", "🐛 修复"),
    ("perf", "⚡ 性能"),
    ("refactor", "♻️ 重构"),
    ("docs", "📝 文档"),
    ("test", "✅ 测试"),
]
_OTHER_LABEL = "🔧 其他"

# 形如 `feat(player): xxx` / `fix: xxx` / `feat!: xxx`
_COMMIT_PREFIX = re.compile(r"^(\w+)(?:\([^)]*\))?!?:\s*(.+)$")


def _git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout


def _previous_tag(tag: str) -> str | None:
    try:
        described = _git("describe", "--tags", "--abbrev=0", f"{tag}^").strip()
    except subprocess.CalledProcessError:
        return None
    return described or None


def _group_commits(subjects: list[str]) -> dict[str, list[str]]:
    label_of = dict(_LABELS)
    grouped: dict[str, list[str]] = {}
    for subject in subjects:
        match = _COMMIT_PREFIX.match(subject)
        prefix = match.group(1) if match else None
        entry = match.group(2) if match else subject
        grouped.setdefault(label_of.get(prefix, _OTHER_LABEL), []).append(entry)
    return grouped


def main() -> None:
    tag = os.environ.get("GITHUB_REF_NAME", "").strip()
    repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
    out_path = sys.argv[1] if len(sys.argv) > 1 else "release_body.md"

    notes_path = os.path.join(".github", "release-notes", f"{tag}.md")

    previous = _previous_tag(tag) if tag else None
    rev_range = f"{previous}..{tag}" if previous else (tag or "HEAD")

    subjects = [
        line
        for line in _git("log", "--no-merges", "--pretty=format:%s", rev_range).splitlines()
        if line.strip()
    ]

    compare_url = (
        f"https://github.com/{repo}/compare/{previous}...{tag}"
        if previous
        else f"https://github.com/{repo}/commits/{tag}"
    )

    lines: list[str] = []

    if os.path.isfile(notes_path):
        with open(notes_path, encoding="utf-8") as handle:
            summary = handle.read().strip()
        if summary:
            lines.extend([summary, ""])

    if subjects:
        grouped = _group_commits(subjects)
        lines.extend(["<details>", "<summary>全部提交</summary>", ""])
        for _, label in _LABELS:
            entries = grouped.get(label)
            if entries:
                lines.append(f"**{label}**")
                lines.extend(f"- {entry}" for entry in entries)
                lines.append("")
        others = grouped.get(_OTHER_LABEL)
        if others:
            lines.append(f"**{_OTHER_LABEL}**")
            lines.extend(f"- {entry}" for entry in others)
            lines.append("")
        lines.append("</details>")
        lines.append("")

    lines.append(f"**Full Changelog**: {compare_url}")

    with open(out_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines).strip() + "\n")


if __name__ == "__main__":
    main()
