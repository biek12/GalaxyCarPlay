# U1 审计生产反例

基准：提交`6184fa9`，3个正常组件兼容断言实际失败。生产代码保持提交内容；init仅增加本目录测试源，不替换生产类/资源、不改依赖或业务逻辑。

```bash
bash tools/review/galaxy-u1-20261112/run.sh
```

默认使用Lead的干净审计副本`.work/reviews/u1-6184fa9`；也可传待审工作副本绝对路径。SDK/Gradle/Robolectric使用原工程`.work`缓存。退出1在原提交上是**真实断言失败**，不是通过；修复后要求退出0。测试为XML行高、既有View的日夜刷新、真实2dp参数，不复制测量/样式算法。

报告：目标副本`galaxy/ui/build/test-results/testDebugUnitTest/TEST-com.galaxy.auto.ui.U1AuditRegressionTest.xml`。Lead原始完整编译/37例、格式检查、反例及未提交差异证据在`.work/reviews/u1-audit-evidence/`。R1需将对应场景纳入既有`GalaxyXmlComponentsTest`，最终全批脚本必须覆盖；本目录是可独立复跑的审计证据，不加入产品APK。
