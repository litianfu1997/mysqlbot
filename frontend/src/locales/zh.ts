export default {
    common: {
        confirm: '确认',
        cancel: '取消',
        save: '保存',
        delete: '删除',
        success: '成功',
        error: '错误',
        loading: '加载中...'
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
        noDb: '无数据源',
        startChat: '开始聊天',
        selectDb: '选择一个数据库开始聊天:',
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
        }
    },
    settings: {
        title: '系统设置',
        tabs: {
            llm: '大模型配置',
            database: '数据库配置',
            system: '系统设置'
        },
        llm: {
            model: '模型名称',
            apiKey: 'API Key',
            baseUrl: 'Base URL',
            temperature: '随机性 (Temperature)',
            maxTokens: '最大 Token 数',
            testConnection: '测试连接',
            testSuccess: '连接成功',
            testFailed: '连接失败'
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
        }
    }
}
