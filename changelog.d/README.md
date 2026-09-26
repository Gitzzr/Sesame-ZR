# changelog.d —— CHANGELOG 待汇总片段

放**尚未汇总进 `CHANGELOG.md`** 的变更记录，一个 PR 一个文件。

## 为什么要有这个目录

`CHANGELOG.md` 是一张日期表，插在表格最上面。只要同时开着 2 个以上 PR，
每个分支都会往**同一个位置**插一行 —— git 三方合并遇到「同一位置的双向插入」必然冲突。
实测一次 4 分支并行：`CHANGELOG.md` 冲突 3 次，而 11 个代码文件 0 冲突。

所以把「写什么」和「写进 CHANGELOG.md」拆成两步：

| 步骤 | 谁做 | 做什么 |
| --- | --- | --- |
| **PR 收尾** | PR 作者 | 新增一个 `changelog.d/*.md` 片段（**新文件，永不冲突**） |
| **汇总**（发版 / 打 tag 前） | 维护者 | 跑脚本把片段并入 `CHANGELOG.md`，再删掉片段 |

片段跟代码在同一个 PR 里，reviewer 照样能看到；判定标准与写法完全沿用
[`docs/development.md` §4](../docs/development.md)。

## 怎么写

文件名自取、能认出来即可，例如 `20260926-taget-idempotent.md`：

```markdown
---
date: 2026-09-26
module: 任务调度 / 蚂蚁森林
---

修复「定时使用道具」子任务被反复注册又取消：同 ID 已存在时不再重复注册；……
```

规则（细节见 `docs/development.md` §4）：

- `date`：填当天，汇总时可用 `--date` 统一覆盖为真实发版日期
- `module`：用户能认出的功能名，多个用 ` / ` 连接，**不写类名**，不能含 `|`
- 正文：**一行**（表格单元格不能换行）、≤ 200 字、用「新增 / 修复 / 调整 / 移除」起头
- 文档整理、CI、内部重构、日志格式这类**不算用户可见**的改动**不要**建片段

## 怎么用

```bash
# 校验片段格式（CI 也会跑）
python scripts/changelog_fragments.py --check

# 预览将插入 CHANGELOG.md 的行（默认只打印，不改文件）
python scripts/changelog_fragments.py

# 汇总：写入 CHANGELOG.md 并删除已汇总的片段
python scripts/changelog_fragments.py --write
```

汇总是一次**独立提交**（`docs: 汇总 CHANGELOG 片段`），走正常 PR 流程合入；
历史行**不追溯**，已发过版的记录保持原样。

> 汇总前脚本会把片段按日期倒序排好 —— 日期与倒序在汇总时统一确定，
> 不用担心并行 PR 各自插在「最上面」导致顺序错乱。
