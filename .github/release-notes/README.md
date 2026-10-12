# Release Notes（发版摘要）

发版前，发版代理（agent）在本目录为本版本撰写一份摘要文件：

```
.github/release-notes/<tag>.md
```

例如 `v1.1.0.md`。CI 的 `.github/workflows/release.yml` 会调用
`.github/scripts/generate_release_body.py` 读取该文件，作为 GitHub Release
正文的「更新摘要」，并附上折叠的提交列表与 Full Changelog 链接。

## 约定

- 文件名必须与 tag **完全一致**（含 `v` 前缀），CI 才能读到。
- 内容面向用户，按「新功能 / 修复 / 优化」等分组，无需逐条罗列提交；
  提交列表 CI 会自动附上。
- 文件缺失时 CI 会降级为仅输出提交列表（仍可用，但没有人工/AI 摘要）。
- 摘要文件随 tag 一起提交，保证该版本可复现（`git show <tag>:.github/release-notes/<tag>.md`）。

## 为什么不用 GitHub 自动生成

仓库提交多为直接 push（无 PR），`softprops/action-gh-release` 的
`generate_release_notes` 只会输出一个 compare 链接。故改为由本目录的摘要 +
提交列表生成正文。

模板见 [TEMPLATE.md](./TEMPLATE.md)。
