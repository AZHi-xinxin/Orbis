# 来源与许可说明

Orbis 基于多个开源项目和已有资源开发。本文件是来源索引与当前核对范围的说明，不替代各项目的完整许可证、版权声明或文件头，也不代表全部素材已经完成许可审计。

## RikkaHub

原生 Android 聊天客户端基于 RikkaHub 源码开发。当前开发基线来源于 RikkaHub 2.5.0，保留其原有版权声明及根目录的 [GNU AGPL-3.0 许可证](../LICENSE)。

Orbis 在此基础上进行功能与界面适配，不是 RikkaHub 官方版本。不要删除上游来源，不要把下述单个组件的 MIT 或 Apache 许可证当作整个工程的许可证。

## LoverConnect / lcbridge

`lcbridge` 的当前实现来自 LC 2.4.3 源码，并针对 Orbis 的身份、入口和宿主应用进行适配。该适配版本与已公开的 LoverConnect 2.4.1 不是同一个版本。

源码保留以下文件：

- [LoverConnect 2.4.1 的原始 MIT 声明](../lcbridge/LICENSES/LoverConnect-2.4.1-MIT.txt)。
- [lcbridge 来源与版本范围说明](../lcbridge/NOTICE.txt)。

本次另已核对 LoverConnect-Enhanced 公开 `v2.4.3` 标签（提交 `6552fb51a914955be9a3f9383b5cd599c23c2de0`）的 MIT 声明，保存在 [2.4.3 原始许可](../lcbridge/LICENSES/LoverConnect-2.4.3-MIT.txt)。它明确保留 LoverConnect 和 AZHi-xinxin 的版权信息。Orbis 的宿主适配、原生工具接入与后续修改不是未改动的上游版本；本仓库保留适配源码和改动说明，整体按根目录 AGPL-3.0 分发，已有 MIT 组件的原声明继续适用。这里不把公开 2.4.3 和此前私人构建称为逐字节相同，也不以旧 2.4.1 文本代替新的来源证据。

## Material Color Utilities

`material3/material-color-utilities` 来自 Material Color Utilities，当前工程直接使用其中的 Kotlin 源码。

保留其 [Apache License 2.0 全文](../material3/material-color-utilities/LICENSE)、[Kotlin 目录许可证](../material3/material-color-utilities/kotlin/LICENSE) 及源码中的原有版权声明。不要只复制需要的代码而遗漏这些文件。

其他随附语言目录的原始许可与资源来源见 [Material 来源说明](../material3/material-color-utilities/NOTICE.orbis.txt)。

## 原生动态库

工程继承的八个 Simple、MuPDF 与 PRoot 原生文件已与 RikkaHub 固定提交核对一致，没有在 Orbis 中修改。二进制内的版本标识进一步指向 MuPDF `1.26.8` 和 PRoot `5.1.107.92`；已补充对应官方固定源码、Android 构建脚本以及 PRoot 构建依赖来源，见 [原生库来源说明](../app/src/main/assets/licenses/native-libraries/NOTICE.txt) 与 [源码及构建索引](../third_party/native-build-references/README.md)。

MuPDF 的 AGPL、PRoot 的 GPL、Simple 的原始双许可、libandroid-shmem 的 BSD 声明随源码和 APK 保留；Simple 按其 MIT 选项使用。固定源码索引同时给出原生组件和依赖的取得方式，不能把仅包含 `.so` 文件的应用源码包称为全部原生对应源码。再次分发 APK 时必须同时保留该源码索引及可用的源码取得渠道；若上游渠道不可用，应先补足源码副本。历史预编译文件没有在本项目中完成逐字节可复现重建，Simple 历史构建的精确提交尚未确立；这些边界不被改写成已完成全量许可认证。

## 海龟汤规则与示例内容

本地海龟汤参考了 Haiguitang 协作项目的规则、主持提示结构和示例题目。对应的规则与主持提示适配保留 MIT 声明；五个示例谜题的内容声明为 CC BY 4.0。示例文本未改写，存储表示从 JSON 转为 Kotlin。

完整来源、内容归属、转换说明与 MIT 文本见 [SOUP_SOURCE_NOTICES.md](../app/src/main/java/me/rerere/rikkahub/data/orbis/soup/SOUP_SOURCE_NOTICES.md)。应与对应代码和题目一起保留。不要将示例内容的许可推及用户自行导入的题库、书籍或其他作品。

## 字体、图片和其他资源

Google Sans Flex 与 JetBrains Mono 的字体许可记录分别位于：

- [Google Sans Flex 声明目录](../app/src/main/assets/licenses/google-sans-flex/NOTICE.txt)，包含 OFL 文本与相关商标说明。
- [JetBrains Mono 声明目录](../app/src/main/assets/licenses/jetbrains-mono/NOTICE.txt)，包含 OFL 文本。

字体文件与上游来源一致，只说明其来源关系；分发时仍应保留对应声明，并核对所选文件的许可范围。

现有横幅图片可追溯到 RikkaHub 的源码基线，但目前没有单独的摄影或艺术授权核验结论。自定义启动图标也需要保留其设计与生成来源记录；本文件不为它额外指定未经确认的独立许可证。具体来源记录见 [ASSET_PROVENANCE.txt](../app/src/main/assets/licenses/ASSET_PROVENANCE.txt)。

此外，服务商图标、表情、内嵌脚本、词典及其他静态资源仍需逐项检查。不要因源码可以构建，或因根目录存在许可证，就把全部字体和资产写成“许可审计已通过”。对尚未确认来源或分发条件的资源，应先补齐证据，必要时替换或从拟分发内容中移除，再决定交付范围。

## 依赖与后续修改

Gradle 与前端依赖另有各自的许可证。本文件不是完整的第三方依赖清单。保留依赖声明和锁定文件，交付前核对实际打包内容及其所需声明。

修改或重新分发本项目时，请保留原始许可文件、版权信息、内容署名和已有的改动说明；不要用一份新的简短 NOTICE 覆盖它们。对于授权范围仍有疑问的内容，本文件只记录已知事实，不提供法律结论。
