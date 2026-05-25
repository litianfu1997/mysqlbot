export default {
    common: {
        confirm: 'Confirm',
        cancel: 'Cancel',
        save: 'Save',
        delete: 'Delete',
        success: 'Success',
        error: 'Error',
        loading: 'Loading...',
        default: 'Default',
        unknownError: 'Unknown error',
        pleaseWait: 'Please wait...',
        processing: 'Processing...'
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
        selectDb: 'Choose a database to start chatting:',
        selectLlmConfig: 'Select LLM Config:',
        welcomeTitle: 'MySqlBot Intelligence',
        welcomeText: 'I can analyze your data, generate SQL snippets, create charts, and answer questions.',
        inputPlaceholder: 'Ask a question about your data...',
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
        },
        messages: {
            switchDataSourceSuccess: 'Switched data source and created new chat',
            switchDataSourceTo: 'Switched to data source: {name}',
            switchDataSourceFailed: 'Failed to switch data source',
            switchLlmConfigTo: 'Switched LLM config: {name}',
            deleteTitle: 'Delete Session',
            deleteConfirm: 'Are you sure you want to delete this chat?',
            deleteConfirmBtn: 'Delete',
            sessionDeleted: 'Session deleted'
        }
    },
    settings: {
        title: 'Settings',
        tabs: {
            llm: 'LLM Config',
            database: 'Database Config',
            system: 'System',
            wecom: 'WeCom Integration',
            feishu: 'Feishu Integration'
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
            test: 'Test Connection',
            sync: 'Sync Schema',
            status: 'Status',
            actions: 'Actions'
        },
        system: {
            language: 'Language'
        },
        wecom: {
            corpIdLabel: 'Corp ID (CorpId)',
            corpIdPlaceholder: 'WeCom CorpId',
            agentIdLabel: 'Agent ID (AgentId)',
            agentIdPlaceholder: 'App AgentId',
            secretLabel: 'App Secret',
            secretPlaceholder: 'App Secret',
            tokenLabel: 'Callback Token',
            tokenPlaceholder: 'Callback server Token',
            aesKeyLabel: 'AES Key',
            aesKeyPlaceholder: 'EncodingAESKey',
            enableLabel: 'Enable WeCom',
            saveButton: 'Save WeCom Config'
        },
        feishu: {
            appIdLabel: 'App ID',
            appIdPlaceholder: 'Feishu App ID',
            appSecretLabel: 'App Secret',
            appSecretPlaceholder: 'Feishu App Secret',
            verificationTokenLabel: 'Verification Token',
            verificationTokenPlaceholder: 'Verification Token',
            encryptKeyLabel: 'Encrypt Key',
            encryptKeyPlaceholder: 'Encrypt Key',
            enableLabel: 'Enable Feishu',
            saveButton: 'Save Feishu Config'
        },
        sync: {
            status: {
                preparing: 'Preparing...',
                extracting: '🚀 Extracting schema',
                embedding: '🧠 Embedding vectors',
                done: '✅ Sync complete',
                error: '❌ Sync failed',
                processing: 'Processing...'
            },
            messages: {
                started: 'Sync started in background',
                completed: 'Schema sync complete!',
                failed: 'Sync failed: {error}'
            }
        }
    }
}

