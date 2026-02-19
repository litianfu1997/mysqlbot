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
        noDb: 'No DB',
        startChat: 'Start Chat',
        selectDb: 'Choose a database to start chatting:',
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
        }
    },
    settings: {
        title: 'Settings',
        tabs: {
            llm: 'LLM Config',
            database: 'Database Config',
            system: 'System'
        },
        llm: {
            model: 'Model',
            apiKey: 'API Key',
            baseUrl: 'Base URL',
            temperature: 'Temperature',
            maxTokens: 'Max Tokens',
            testConnection: 'Test Connection',
            testSuccess: 'Connection successful',
            testFailed: 'Connection failed'
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
        }
    }
}
