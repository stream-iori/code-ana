# 统一输入准备验收（2026-10-19）

| 结果 | 证据 |
|---|---|
| 范围与门禁 | [receipt.json](receipt.json) |
| 最终真实项目 | [receipt](real-project-final/receipt.json)、[25 个导航探针](real-project-final/call-audit.json) |
| 全局报告示例 | [JSON](real-project-final/report.json)、[Markdown](real-project-final/report.md) |
| 代码与分发 | source-sha256.json、package-manifest.json、package-SHA256SUMS |
| 原失败与重试 | logs/*.log.gz；gzip 保留原始日志字节 |

功能重放使用冻结提交 31259b761a5ea77672d0e90bc9e2776e4dfe397e 的 615 文件，不修改 sibling 项目。30 complete、585 partial；无新正式性能测量。Java Native SDK 单独安装；分发混合用例临时关联原有 SDK，结束后移除关联。全局报告只列前 20 类诊断并明确省略数，详细定位使用查询与日志。
