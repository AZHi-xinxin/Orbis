# 离线语义记忆：模型与运行时来源

本页仅说明新增离线召回组件，不替代[整体来源与许可](NOTICE.md)，也不为其余图片、音频或依赖声明统一许可。

## 模型及词表

- 原模型为 BAAI 的 [bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)，模型卡声明 MIT；上游实现为 [FlagEmbedding](https://github.com/FlagOpen/FlagEmbedding)。原始许可文本保留在 [LICENSE-FlagEmbedding.txt](../app/src/main/assets/orbis-memory/LICENSE-FlagEmbedding.txt)。
- 分发的是 [Xenova/bge-small-zh-v1.5](https://huggingface.co/Xenova/bge-small-zh-v1.5/tree/75c43b069aac4d136ba6bc1122f995fedcfd2781) 的量化 ONNX 转换，固定 revision `75c43b069aac4d136ba6bc1122f995fedcfd2781`；原文件 `onnx/model_quantized.onnx` 在 APK 中改名为 `assets/orbis-memory/model.onnx`，不宣称由 Orbis 重新训练。
- 模型大小 `24,010,842` 字节，SHA-256 `15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc`。
- 同目录 `vocab.txt` 的 SHA-256 为 `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c`。模型、词表及[随包 NOTICE](../app/src/main/assets/orbis-memory/NOTICE.txt)应一起保留。
- 当前使用 CPU int8、CLS 池化、512 维及 L2 归一化。召回缓存可重建，不替代原始记忆；本组件不会为嵌入计算另行下载模型或上传文本。

## ONNX Runtime

运行时由 Gradle 依赖 `com.microsoft.onnxruntime:onnxruntime-android:1.23.2` 提供，来源为 Microsoft 的 [ONNX Runtime v1.23.2](https://github.com/microsoft/onnxruntime/tree/v1.23.2)。依赖 POM 和该版本[许可证](https://github.com/microsoft/onnxruntime/blob/v1.23.2/LICENSE)声明 MIT；其[第三方声明](https://github.com/microsoft/onnxruntime/blob/v1.23.2/ThirdPartyNotices.txt)另外保留所涉及组件的声明，不能只写一个 MIT 标签替代它们。

核对的原始 AAR 大小为 `30,241,654` 字节，SHA-256 为 `82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827`。已将固定版本的[原始许可与声明](../app/src/main/assets/licenses/onnxruntime/NOTICE.txt)作为 Orbis 资产随附。分发时仍需检查最终 APK，不能以 Maven POM 中的链接代替实际文件。

在 Orbis 支持的 ABI 中，本依赖新增以下原生库（AAR 使用 `jni/` 前缀，APK 使用 `lib/` 前缀）：

| AAR 路径 | 原始字节数 | 原始 SHA-256 |
| --- | ---: | --- |
| `jni/arm64-v8a/libonnxruntime.so` | 19,347,616 | `e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302` |
| `jni/arm64-v8a/libonnxruntime4j_jni.so` | 100,648 | `2cc3f16ab527959a81b572adebcfb71a25ea4b8547c9b92d705b423e9c822d39` |
| `jni/x86_64/libonnxruntime.so` | 23,181,616 | `972c17c056eaae946a415d9efdd8018b729639974df075e495f0092441478fb7` |
| `jni/x86_64/libonnxruntime4j_jni.so` | 90,728 | `b8d1b251b7d5606cf5caa7db9738353c08efc4090ac90ca2aaab449bc263a10b` |

Android 构建可能对库做符号剥离，因此 AAR 原文件哈希不能直接当作最终 APK 哈希；应分别保存依赖来源和最终构建产物的校验记录。本项目没有宣称该第三方预编译运行时已由 Orbis 逐字节复现构建。

已检查的两种 ABI 中，`libonnxruntime.so` 与 `libonnxruntime4j_jni.so` 的 ELF `PT_LOAD` 对齐均满足 16 KiB。旧 1.20.0 JNI 包装库只有 4 KiB 对齐，本版已成套更新。ELF 对齐与 APK ZIP 对齐仍须分别检查；静态检查不等于已经在 16 KiB 内核真机上验收，也不保证整个应用已兼容所有此类设备。

## 发布核对边界

与旧正式包比较时，按同 ABI、同构建类型核对已有原生文件；仅对明确的 ONNX 新增集合单独核验依赖来源、剥离后文件、架构和许可证。不要因增加一个依赖而关闭全部原生文件比较，也不要把 Dev 与 Release 的本地 C/C++ 编译差异直接认作上游二进制改动。主包、Rescue 和 universal 均需检查各自实际包含的 ABI 与许可资产。

新增离线模型和运行时会增加安装包体积；源码包应保留模型、词表、来源说明及对应许可，不应只有一个不可追溯的 `model.onnx`。
