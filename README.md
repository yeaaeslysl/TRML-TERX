# TRML-TERX
TRML 是一款基于 Shizuku 的 Android 本地 MCP（Model Context Protocol）服务器应用，让 AI Agent 能够安全地远程操控手机终端、读写文件、查看图片并执行系统级命令。它采用纯 Java 原生实现，零 Python 依赖，通过 ADB 级权限提供比 Termux 更轻量的工具链调用能力，支持 SSE/HTTP 双通道传输与多客户端并发连接。

项目内置完整的终端模拟器，配备 ExtraKeys 快捷键栏、六套预设主题（Nord / Monokai / Dracula / Solarized Dark / Light / Default）及自定义 HEX 配色系统，控制面板 UI 支持实时颜色预览与持久化。所有 MCP 工具调用均同步回显至终端，操作日志完整可追溯，安全黑名单机制拦截危险命令选项。

TERX 是 TRML 的持久化增强分支，计划集成 proot + Linux rootfs，提供完整的包管理器（apt/pkg）、独立 $PREFIX 目录与用户级 rc 文件加载，使安装的软件、环境变量和别名在重启后依然生效，实现接近原生 Linux 的移动开发体验。

本项目面向 AI 辅助移动运维、自动化测试与嵌入式开发场景，代码开源、架构模块化，欢迎贡献新 Tool、主题或运行时后端。