# U1-R1 按钮兼容审计

固定提交`6708121`，两个真实控件断言失败：普通按钮字体附加留白回退、文字按钮26sp行高丢失。生产代码/资源不替换，init只增加本目录测试源。

```bash
bash tools/review/galaxy-u1-r1-20261112/run.sh [待审工作副本绝对路径]
```

省略路径时使用原R1工作副本。原R1应exit1并产生两个真实断言失败；R2修复后应exit0。完整JUnit在目标副本`galaxy/ui/build/test-results/testDebugUnitTest/TEST-com.galaxy.auto.ui.U1R1AuditRegressionTest.xml`。测试调用实际GalaxyViews/GalaxyStyle工厂；26sp参照使用原生产动态文字方法，不另写被测模型。

原43例、上轮3例和本轮反例日志归档：`.work/reviews/r1-audit-evidence/`。本目录不是产品源集，不进入APK。属性数组的平台契约问题由源码和官方API核对，不用检查私有字段名字的字符串测试冒充运行时布局验证。
