export default {
    common: {
        confirm: '确认',
        cancel: '取消',
        save: '保存',
        delete: '删除',
        success: '成功',
        error: '错误',
        loading: '加载中...',
        default: '默认',
        unknownError: '未知错误',
        pleaseWait: '请稍候...',
        processing: '处理中...'
    },
    chat: {
        newChat: '新会话',
        placeholder: '询问有关您数据的问题...',
        send: '发送',
        user: '用户',
        assistant: 'MySqlBot',
        generatedSql: '生成的 SQL',
        analysis: '数据分析',
        generateChart: '生成图表',
        chartGenerated: '检测到数据可以生成 {chartType} 图表',
        showChart: '显示图表',
        analyzing: '正在分析数据...',
        rowCount: '共返回 {count} 行',
        deleteSessionConfirm: '您确定要删除此会话吗？',
        switchDataSource: '切换数据源',
        manageDataSources: '管理数据源...',
        switchLlmConfig: '切换LLM配置',
        manageLlmConfigs: '管理LLM配置...',
        noDb: '无数据源',
        startChat: '开始聊天',
        selectDb: '选择一个数据库开始聊天:',
        selectLlmConfig: '选择LLM配置:',
        welcomeTitle: 'MySqlBot 智能助手',
        welcomeText: '我可以分析您的数据、生成 SQL 代码、创建图表并回答问题。',
        inputPlaceholder: '请输入您想了解的数据问题...',
        examples: {
            topProducts: '按销量列出排名前5的产品',
            trendAnalysis: '显示去年的月度趋势',
            userGrowth: '分析用户留存率',
            revenue: '按地区显示收入分布'
        },
        exampleTitles: {
            topProducts: '热销产品',
            trendAnalysis: '趋势分析',
            userGrowth: '用户增长',
            revenue: '收入分布'
        },
        messages: {
            switchDataSourceSuccess: '已切换数据源并创建新对话',
            switchDataSourceTo: '已切换到数据源：{name}',
            switchDataSourceFailed: '切换数据源失败',
            switchLlmConfigTo: '已切换LLM配置：{name}',
            deleteTitle: '删除会话',
            deleteConfirm: '确定要删除此会话吗？',
            deleteConfirmBtn: '删除',
            sessionDeleted: '会话已删除'
        }
    },
    settings: {
        title: '系统设置',
        tabs: {
            llm: '大模型配置',
            database: '数据库配置',
            system: '系统设置',
            wecom: '企业微信集成',
            feishu: '飞书集成'
        },
        llm: {
            add: '添加配置',
            edit: '编辑配置',
            name: '配置名称',
            model: '模型名称',
            apiKey: 'API Key',
            baseUrl: 'Base URL',
            temperature: '随机性 (Temperature)',
            maxTokens: '最大 Token 数',
            testConnection: '测试连接',
            testSuccess: '连接成功',
            testFailed: '连接失败',
            setDefault: '设为默认',
            default: '默认',
            status: '状态',
            enabled: '已启用',
            disabled: '已禁用'
        },
        database: {
            add: '添加数据库',
            edit: '编辑数据库',
            name: '名称',
            host: '主机',
            port: '端口',
            username: '用户名',
            password: '密码',
            database: '数据库名',
            test: '测试连接',
            sync: '同步表结构',
            status: '状态',
            actions: '操作'
        },
        system: {
            language: '语言设置'
        },
        wecom: {
            corpIdLabel: '企业ID (CorpId)',
            corpIdPlaceholder: '企业微信 CorpId',
            agentIdLabel: '应用ID (AgentId)',
            agentIdPlaceholder: '应用 AgentId',
            secretLabel: '应用密钥 (Secret)',
            secretPlaceholder: '应用 Secret',
            tokenLabel: '回调 Token',
            tokenPlaceholder: '接收消息服务器配置的 Token',
            aesKeyLabel: 'AES Key',
            aesKeyPlaceholder: 'EncodingAESKey',
            enableLabel: '启用企业微信',
            saveButton: '保存企业微信配置'
        },
        feishu: {
            appIdLabel: '应用 ID (App ID)',
            appIdPlaceholder: '飞书 App ID',
            appSecretLabel: '应用密钥 (App Secret)',
            appSecretPlaceholder: '飞书 App Secret',
            verificationTokenLabel: '验证 Token',
            verificationTokenPlaceholder: 'Verification Token',
            encryptKeyLabel: '加密密钥 (Encrypt Key)',
            encryptKeyPlaceholder: 'Encrypt Key',
            enableLabel: '启用飞书',
            saveButton: '保存飞书配置'
        },
        sync: {
            status: {
                preparing: '准备中...',
                extracting: '🚀 提取表结构',
                embedding: '🧠 向量化模型处理',
                done: '✅ 同步完成',
                error: '❌ 同步失败',
                processing: '处理中...'
            },
            messages: {
                started: '同步进程已在后台启动',
                completed: 'Schema 同步完成！',
                failed: '同步失败: {error}'
            }
        }
    }
}

