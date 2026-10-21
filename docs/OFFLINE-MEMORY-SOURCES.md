# 离线语义记忆：模型与运行时来源

本页仅说明新增离线召回组件，不替代[整体来源与许可](NOTICE.md)，也不为其余图片、音频或依赖声明统一许可。

## 模型及词表

- 原模型为 BAAI 的 [bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)，模型卡声明 MIT；上游实现为 [FlagEmbedding](https://github.com/FlagOpen/FlagEmbedding)。原始许可文本保留在 [LICENSE-FlagEmbedding.txt](../app/src/main/assets/orbis-memory/LICENSE-FlagEmbedding.txt)。
- 分发的是 [Xenova/bge-small-zh-v1.5](https://huggingface.co/Xenova/bge-small-zh-v1.5/tree/75c43b069aac4d136ba6bc1122f995fedcfd2781) 的量化 ONNX 转换，固定 revision `75c43b069aac4d136ba6bc1122f995fedcfd2781`；原文件 `onnx/model_quantized.onnx` 在 APK 中改名为 `assets/orbis-memory/model.onnx`，不宣称由 Orbis 重新训练。
- 模型大小 `24,010,842` 字节，SHA-256 `15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc`。
- 同目录 `vocab.txt` 的 SHA-256 为 `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c`。模型、词表及[随包 NOTICE](../app/src/main/assets/orbis-memory/NOTICE.txt)应一起保留。
- 当前使用 CPU int8、CLS 池化、512 维及 L2 归一化。召回缓存可重建，不替代原始记忆；本组件不会为嵌入计算另行下载模型或上传文本。

## ONNX Runtime

运行时由 Gradle 依赖 `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` 提供，来源为 Microsoft 的 [ONNX Runtime v1.20.0](https://github.com/microsoft/onnxruntime/tree/v1.20.0)。依赖 POM 和该版本[许可证](https://github.com/microsoft/onnxruntime/blob/v1.20.0/LICENSE)声明 MIT；其[第三方声明](https://github.com/microsoft/onnxruntime/blob/v1.20.0/ThirdPartyNotices.txt)另外保留所涉及组件的声明，不能只写一个 MIT 标签替代它们。

核对的原始 AAR 大小为 `27,867,924` 字节，SHA-256 为 `07a8f71ef890afed8c6087a56220e6d558a492804276ee2dd7cb7f6262242027`。原始 AAR 没有顶层 LICENSE/NOTICE 文件条目；已将固定版本的[原始许可与声明](../app/src/main/assets/licenses/onnxruntime/NOTICE.txt)作为 Orbis 资产随附。分发时仍需检查最终 APK，不能以 Maven POM 中的链接代替实际文件。

在 Orbis 支持的 ABI 中，本依赖新增以下原生库（AAR 使用 `jni/` 前缀，APK 使用 `lib/` 前缀）：

| AAR 路径 | 原始字节数 | 原始 SHA-256 |
| --- | ---: | --- |
| `jni/arm64-v8a/libonnxruntime.so` | 17,571,160 | `52329b7c8e5fcd5c1aa88b01093215faaef362e3f3d0e374cb8b10d1e4704677` |
| `jni/arm64-v8a/libonnxruntime4j_jni.so` | 915,968 | `ac94a0df9d6f2e4eb2a18723807e4cb2e38b5a78bceb9c367bcbf84633171649` |
| `jni/x86_64/libonnxruntime.so` | 20,290,904 | `047705ab42981a4884eec435f60cc1a311e40414cd52fa2f252da2b473756844` |
| `jni/x86_64/libonnxruntime4j_jni.so` | 893,360 | `c09ed0c68d026b6320c5505fd78c3b0a1c52c1c94d3911566ccf6a94b55ac2e9` |

Android 构建可能对库做符号剥离，因此 AAR 原文件哈希不能直接当作最终 APK 哈希；应分别保存依赖来源和最终构建产物的校验记录。本项目没有宣称该第三方预编译运行时已由 Orbis 逐字节复现构建。

已检查的两种 ABI 中，`libonnxruntime.so` 的 ELF `PT_LOAD` 对齐为 `0x4000`（16 KiB），`libonnxruntime4j_jni.so` 为 `0x1000`（4 KiB）。后者不满足完整的 16 KiB 页大小要求，不能以 APK 通过 ZIP 对齐检查代替 ELF 兼容性。16 KiB 设备上的可选离线语义召回未验收，可能不可用；召回路径在链接失败时返回空结果，但不据此保证整个应用已兼容此类设备。

## 发布核对边界

与旧正式包比较时，按同 ABI、同构建类型核对已有原生文件；仅对明确的 ONNX 新增集合单独核验依赖来源、剥离后文件、架构和许可证。不要因增加一个依赖而关闭全部原生文件比较，也不要把 Dev 与 Release 的本地 C/C++ 编译差异直接认作上游二进制改动。主包、Rescue 和 universal 均需检查各自实际包含的 ABI 与许可资产。

新增离线模型和运行时会增加安装包体积；源码包应保留模型、词表、来源说明及对应许可，不应只有一个不可追溯的 `model.onnx`。
