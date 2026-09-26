#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CHANGELOG 片段工具：校验与汇总。

背景
----
`CHANGELOG.md` 是一张日期表，约定「一个 PR 一行、插在表格最上面」。但只要有 2 个以上
PR 同时开着，每个分支都会往同一个位置插一行 —— git 三方合并遇到「同一位置的双向插入」
必然报冲突。实测 4 个并行分支：CHANGELOG.md 冲突 3 次，其余代码文件 0 冲突。

因此把两件事拆开：

1. **判断写什么**（是否用户可见、模块名、怎么措辞）—— 只有写代码的人有上下文，
   必须留在 PR 里，产出到 `changelog.d/<短名>.md`（**新文件，永不冲突**）；
2. **写进 CHANGELOG.md** —— 对所有并发 PR 都是同一个共享文件的写入，
   必须延后到汇总时一次性完成。

本脚本就是第 2 步，同时承担第 1 步的格式校验。

用法
----
    # 校验所有片段（CI 用；格式或长度不合规时非零退出）
    python scripts/changelog_fragments.py --check

    # 预览将插入 CHANGELOG.md 的行（默认只打印，不改文件）
    python scripts/changelog_fragments.py

    # 真正汇总：写入 CHANGELOG.md 并删除已汇总的片段
    python scripts/changelog_fragments.py --write

    # 汇总时统一覆盖日期（默认沿用片段里写的日期）
    python scripts/changelog_fragments.py --write --date 2026-09-30

片段格式
--------
    ---
    date: 2026-09-26
    module: 任务调度 / 蚂蚁森林
    ---

    修复「定时使用道具」子任务被反复注册又取消：……

- `date`：填当天即可；汇总时可用 `--date` 统一覆盖为真实发版/合入日期。
- `module`：用户能认出的功能名，多个用 ` / ` 连接，**不写类名**。
- 正文：**必须写成一行**（表格单元格不支持换行），≤ 200 字，用「新增 / 修复 / 调整 / 移除」起头。
- 判定标准（什么算用户可见）见 `docs/development.md` §4。
"""

from __future__ import annotations

import argparse
import datetime
import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
FRAGMENT_DIR_NAME = "changelog.d"
CHANGELOG_NAME = "CHANGELOG.md"

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
TABLE_SEPARATOR_RE = re.compile(r"^\|\s*-+\s*\|\s*-+\s*\|\s*-+\s*\|\s*$")

# 与 docs/development.md §4 保持一致
MAX_BODY_LEN = 200
HEADER_KEYS = ("date", "module")


class Fragment:
    def __init__(self, path: pathlib.Path, date: str, module: str, body: str):
        self.path = path
        self.date = date
        self.module = module
        self.body = body

    @property
    def row(self) -> str:
        return f"| {self.date} | {self.module} | {self.body} |"


def parse_fragment(path: pathlib.Path) -> tuple[Fragment | None, str | None]:
    """解析片段文件，返回 (Fragment, None) 或 (None, 错误信息)。"""
    try:
        raw = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return None, f"{path.name}: 不是 UTF-8 编码"

    if raw.startswith("\ufeff"):
        return None, f"{path.name}: 含 UTF-8 BOM，请去掉"

    lines = raw.splitlines()
    if not lines or lines[0].strip() != "---":
        return None, f"{path.name}: 缺少 front matter（文件应以 --- 开头）"

    meta: dict[str, str] = {}
    end = None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            end = i
            break
        if ":" not in lines[i]:
            return None, f"{path.name}: front matter 第 {i + 1} 行不是 key: value"
        key, _, value = lines[i].partition(":")
        meta[key.strip().lower()] = value.strip()
    if end is None:
        return None, f"{path.name}: front matter 没有结束的 ---"

    for key in HEADER_KEYS:
        if not meta.get(key):
            return None, f"{path.name}: front matter 缺少 {key}"

    date = meta["date"]
    if not DATE_RE.match(date):
        return None, f"{path.name}: date 必须是 YYYY-MM-DD，当前为 {date!r}"

    body_lines = [ln.strip() for ln in lines[end + 1:] if ln.strip()]
    if not body_lines:
        return None, f"{path.name}: 正文为空"
    if len(body_lines) > 1:
        return None, (
            f"{path.name}: 正文必须写成一行（表格单元格不支持换行），当前有 {len(body_lines)} 行"
        )
    body = body_lines[0]

    for label, value in (("module 字段", meta["module"]), ("正文", body)):
        if "|" in value:
            return None, f"{path.name}: {label}不能出现 | （会破坏表格）"

    if len(body) > MAX_BODY_LEN:
        return None, f"{path.name}: 正文 {len(body)} 字，超过 {MAX_BODY_LEN} 字上限"

    return Fragment(path, date, meta["module"], body), None


def load_fragments(fragment_dir: pathlib.Path) -> tuple[list[Fragment], list[str]]:
    if not fragment_dir.is_dir():
        return [], []
    errors: list[str] = []
    fragments: list[Fragment] = []
    for path in sorted(fragment_dir.glob("*.md")):
        if path.name.lower() == "readme.md":
            continue
        fragment, error = parse_fragment(path)
        if error:
            errors.append(error)
        elif fragment:
            fragments.append(fragment)
    return fragments, errors


def insert_rows(changelog: pathlib.Path, rows: list[str]) -> int:
    """把新行插到表格分隔行之后，返回插入行数。"""
    lines = changelog.read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        if TABLE_SEPARATOR_RE.match(line):
            return_lines = lines[: i + 1] + rows + lines[i + 1:]
            changelog.write_text("\n".join(return_lines) + "\n", encoding="utf-8")
            return len(rows)
    raise SystemExit(f"✗ 在 {changelog.name} 里找不到表格分隔行（| --- | --- | --- |）")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="CHANGELOG 片段校验与汇总（约定见 docs/development.md §4）"
    )
    parser.add_argument("--check", action="store_true", help="只校验片段格式，不修改任何文件")
    parser.add_argument("--write", action="store_true", help="写入 CHANGELOG.md 并删除已汇总片段")
    parser.add_argument("--date", help="统一覆盖所有片段的日期（YYYY-MM-DD）")
    parser.add_argument("--root", default=str(REPO_ROOT), help="仓库根目录（默认自动推断）")
    args = parser.parse_args()

    if args.date and not DATE_RE.match(args.date):
        print("✗ --date 必须是 YYYY-MM-DD", file=sys.stderr)
        return 2

    root = pathlib.Path(args.root).resolve()
    fragment_dir = root / FRAGMENT_DIR_NAME
    changelog = root / CHANGELOG_NAME

    fragments, errors = load_fragments(fragment_dir)

    if errors:
        print("✗ 片段格式校验未通过：", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    if args.check:
        if not fragments:
            print(f"· {FRAGMENT_DIR_NAME}/ 下没有待汇总片段（仅当本次改动不含用户可见变化时属正常）")
        else:
            print(f"✓ {len(fragments)} 个片段格式正确")
            for fragment in fragments:
                print(f"  - {fragment.path.name}  {len(fragment.body)} 字")
        return 0

    if not fragments:
        print(f"· {FRAGMENT_DIR_NAME}/ 下没有待汇总片段，无需汇总")
        return 0

    if args.date:
        for fragment in fragments:
            fragment.date = args.date

    # 日期倒序；同日期按文件名稳定排序，保证结果可复现
    fragments.sort(key=lambda f: (f.date, f.path.name), reverse=True)
    rows = [f.row for f in fragments]

    # 汇总当天日期，用于提示
    today = datetime.date.today().isoformat()
    print(f"将按以下顺序插入 {changelog.name} 表格顶部（共 {len(rows)} 行，今天 {today}）：")
    for row in rows:
        print(f"  {row}")

    if not args.write:
        print("\n（这是预览。确认无误后加 --write 执行）")
        return 0

    inserted = insert_rows(changelog, rows)
    for fragment in fragments:
        fragment.path.unlink()
    print(f"\n✓ 已插入 {inserted} 行到 {changelog.name}，并删除 {len(fragments)} 个片段文件")
    print("  请检查表格倒序是否正确，然后提交（建议提交信息：docs: 汇总 CHANGELOG 片段）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
