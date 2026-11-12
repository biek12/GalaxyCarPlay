# 主题场景引擎归档（2026-11-12）

这些文件不进 Gradle 源集，也不写入 sourceSets。内容是起点 `92cf470af06f4999256f7c83502aa1edd56b7e44` 的 Git blob 原字节，package 与源码保持复制时的原样。

范围：theme 目录里除 SceneData.kt 以外的生产代码、runtime/ScenePreparation.kt，以及 src/test 下 theme 的测试。相对路径不变。本目录不并入 archive/legacy-fullscreen-projection。

SceneData、SceneMedia、SceneSignal 留在活跃源集，包名为 com.galaxy.autocluster.preview。音乐卡片只依赖这个类型。

以后若恢复编辑器，要把引擎对 SceneData 的引用改到 com.galaxy.autocluster.preview，再迁回源集。不能按旧包名直接编回，也不能让活跃代码 import 本目录。
