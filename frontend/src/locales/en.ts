export default {
    common: {
        confirm: 'Confirm',
        cancel: 'Cancel',
        save: 'Save',
        delete: 'Delete',
        success: 'Success',
        error: 'Error',
        loading: 'Loading...'
    },
    chat: {
        newChat: 'New Chat',
        placeholder: 'Ask a question about your data...',
        send: 'Send',
        user: 'User',
        assistant: 'MySqlBot',
        generatedSql: 'Generated SQL',
        analysis: 'Analysis',
        generateChart: 'Generate Chart',
        chartGenerated: 'A {chartType} chart can be generated from the data',
        showChart: 'Show Chart',
        analyzing: 'Analyzing data...',
        rowCount: '{count} rows returned',
        deleteSessionConfirm: 'Are you sure you want to delete this chat?',
        switchDataSource: 'Switch Data Source',
        manageDataSources: 'Manage Data Sources...',
        switchLlmConfig: 'Switch LLM Config',
        manageLlmConfigs: 'Manage LLM Configs...',
        noDb: 'No DB',
        startChat: 'Start Chat',
        selectDb: 'Choose a database to start chatting',
        selectLlmConfig: 'Select LLM Config:',
        welcomeTitle: 'MySqlBot Intelligence',
        welcomeText: 'I can analyze your data, generate SQL snippets, create charts, and answer questions.',
        inputPlaceholder: 'Ask a question about your data...',
        deepThinking: 'Deep Thinking',
        deepThinkingHint: 'Use the reasoning model and stream the thinking process in real time (slower response)',
        examples: {
            topProducts: 'List top 5 products by sales volume',
            trendAnalysis: 'Show monthly trend for last year',
            userGrowth: 'Analyze user retention rate',
            revenue: 'Show revenue distribution by region'
        },
        exampleTitles: {
            topProducts: 'Top Products',
            trendAnalysis: 'Trend Analysis',
            userGrowth: 'User Growth',
            revenue: 'Revenue'
        }
    },
    settings: {
        title: 'Settings',
        tabs: {
            llm: 'LLM Config',
            database: 'Database Config',
            relation: 'Table Relations',
            wecom: 'WeCom Integration',
            feishu: 'Feishu Integration',
            system: 'System'
        },
        llm: {
            add: 'Add Config',
            edit: 'Edit Config',
            name: 'Config Name',
            model: 'Model',
            apiKey: 'API Key',
            baseUrl: 'Base URL',
            temperature: 'Temperature',
            maxTokens: 'Max Tokens',
            testConnection: 'Test Connection',
            testSuccess: 'Connection successful',
            testFailed: 'Connection failed',
            setDefault: 'Set Default',
            default: 'Default',
            status: 'Status',
            enabled: 'Enabled',
            disabled: 'Disabled'
        },
        database: {
            add: 'Add Database',
            edit: 'Edit Database',
            name: 'Name',
            host: 'Host',
            port: 'Port',
            username: 'Username',
            password: 'Password',
            database: 'Database',
            dbType: 'Type',
            test: 'Test Connection',
            sync: 'Sync Schema',
            status: 'Status',
            actions: 'Actions'
        },
        wecom: {
            corpId: 'Corp ID',
            agentId: 'Agent ID',
            secret: 'App Secret',
            token: 'Callback Token',
            enabled: 'Enable WeCom'
        },
        feishu: {
            appId: 'App ID',
            appSecret: 'App Secret',
            encryptKey: 'Encrypt Key',
            enabled: 'Enable Feishu'
        },
        system: {
            language: 'Language'
        },
        relation: {
            add: 'Add Relation',
            generateAI: 'AI Generate',
            aiPreviewTitle: 'AI-Inferred Relations (select to save)',
            aiNoNew: 'No new relations found',
            aiSaveSelected: 'Save Selected',
            aiSaveSuccess: 'Saved {count} relations',
            generateAIFailed: 'AI generation failed',
            selectTable: 'Select a table',
            selectColumn: 'Select a column',
            edit: 'Edit Relation',
            fromTable: 'From Table',
            fromColumn: 'From Column',
            toTable: 'To Table',
            toColumn: 'To Column',
            source: 'Source',
            confidence: 'Confidence',
            actions: 'Actions',
            sourceTypes: {
                fk: 'Foreign Key',
                naming: 'Naming Convention',
                llm: 'LLM Inferred',
                manual: 'Manual'
            }
        }
    }
}
