# 上游对齐说明（LyraVoid/FolkPatch）

> 核验日期：2026-11-28（Asia/Shanghai）
> 结论：**你的库没有少任何上游功能，官网自同步日起也没有再更新。**

## 一、基线

| 项 | 值 |
|---|---|
| 上游主分支 | `LyraVoid/FolkPatch@main` |
| 上游最新提交 | `0f3f42ba02d6441dbbeb247f34c0f5eabc14e393`（2026-10-30） |
| 上游最新 Release | `115404`，tag 直接指向 `0f3f42ba` |
| 本库同步提交 | `ad54c243`（"sync the source tree with upstream LyraVoid/FolkPatch @ 0f3f42ba"，2026-11-01） |
| 本库当前 main | `a2c697af21` |

用 `compare/0f3f42ba...main` 查上游：`status: identical`、`ahead_by: 0`、`total_commits: 0`。
也就是说，**从你同步那天到现在，官网一行代码都没再提交过**。

上游 `pushed_at` 停在 2026-10-30，`updated_at` 的 11-27 只是 star / issue 等元数据变动，不是代码更新。

## 二、逐文件比对结果

对两个 `main` 做完整递归树比对（按 Git blob SHA，逐字节）：

| 结果 | 数量 |
|---|---|
| 字节完全相同 | **620 / 633** |
| 上游有、本库缺失 | **0** |
| 两边都有但内容不同 | 13（全部是本库私有改动，见下） |
| 本库独有文件 | 28（全部是本库私有功能） |

### 内容不同的 13 个文件

这些都不是"官网更新了"，而是本库 11-01～11-05 自己加的修复与功能：

```
app/build.gradle.kts
app/src/main/java/me/bmax/apatch/APatchApp.kt
app/src/main/java/me/bmax/apatch/ui/WebUIActivity.kt
app/src/main/java/me/bmax/apatch/ui/screen/misc/AppProfile.kt
app/src/main/java/me/bmax/apatch/ui/screen/module/APMTopBar.kt
app/src/main/java/me/bmax/apatch/ui/screen/patches/Patches.kt
app/src/main/java/me/bmax/apatch/ui/screen/superuser/SuperUser.kt
app/src/main/java/me/bmax/apatch/ui/viewmodel/PatchesViewModel.kt
app/src/main/java/me/bmax/apatch/ui/webui/SuFilePathHandler.java
app/src/main/java/me/bmax/apatch/util/APatchCli.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
version.properties
```

对应内容：WebUI 独立进程 SuperKey 修复、无 root shell 不崩、内置 KPM 嵌入 boot 镜像、授权页返回键修复、XiaoCaiYe 入口、`managerVersionEpoch` 抬高，以及中英文文案。

### 本库独有的 28 个文件

XiaoCaiYe payload（`app/src/main/assets/xiaocaiye/` 全套脚本与 so）、两个内置 KPM（`Nohello.kpm`、`selinux_magisk_access_filter.kpm`）、`app/debug.keystore`，以及两个构建工作流。

> 上一轮按要求删除的「App Hide List 一键应用隐藏」和「HMA 配置编辑器」6 个源文件，本来就是本库私有功能；删掉后反而更贴近上游。

## 三、上游其他分支不是"新版"

| 分支 | tip 日期 | 说明 |
|---|---|---|
| `dev` | 2026-10-30 | 就是 main 的父提交 |
| `nnext` | 2026-07-04 | 比 main 老 |
| `miuix` | 2026-03-29 | 比 main 老 |
| `fpd` | 2026-03-30 | 比 main 老 |
| `revert-220-...` | 2026-10-02 | 单个 revert 分支 |

没有任何分支含有比 `0f3f42ba` 更新的正式功能。**把这些分支合进来只会引入旧代码或实验 UI，且会动算法，不符合要求。**

## 四、"算法保证不变"的边界

本次融合是**零逻辑改动**：不改任何 Kotlin / Java / Rust / C++ / Shell 源码，不改 `version.properties`，不改 `kpimg` 与 `libkptools.so` 的取用方式。

保护名单（任何融合操作都不得覆盖）：

- 上述 13 个内容不同的文件
- 上述 28 个本库独有文件
- `version.properties`（`managerVersionEpoch=115370` / `managerVersionFloor=115036`）
- `.github/workflows/build-kp-main.yml`（KernelPatch main 构建基线）
- 已删除的两个功能不得被带回

只从上游取「上游新增或变更、且不在保护名单」的文件。当前这类文件数量 = **0**。

## 五、以后怎么查 / 怎么融合

仓库里放了 `.github/workflows/sync-upstream.yml`（手动触发）：

- 默认 `apply=false`：只出报告，列出上游是否有新提交、逐文件差异清单，并上传为 artifact。**不改任何代码。**
- `apply=true`：把上游新增/变更且不在保护名单的文件复制到新分支 `upstream-sync-<shortsha>`，供你 review 后自行合并。保护名单内的文件一律跳过。

将来上游真有新提交时，跑一次就能看到差了什么，再决定要不要合，不会盲盖。

## 六、关于页面快照

你贴的 GitHub 页面显示 `4b6900e`、50 commits、2 Releases、2 Tags、5 Branches —— 那是 **11-05 的旧快照**（浏览器缓存或未刷新）。

当前实际状态：`a2c697af`、4 Tags（`115404` / `webui-fix-29544f3` / `no-hide-hma` / `kp-main-e087c38`）、4 Releases、6 Branches。以刷新后的页面和 API 为准。

另外，两个仓库的 README 本来就是同一份（英文 README 前 4000 字符相似度 1.0000）。另一份抓取里看起来"内容少"，只是丢了图片、表格和链接，不是官网多了功能说明。

## 七、许可

上游 FolkPatch 为 GPL-3.0，KernelPatch 为 GPL-2.0。分发二进制须同时提供完整可读源码；本仓库保留了可复现构建工作流，二进制对应源码为 `LyraVoid/KernelPatch@e087c38`。
