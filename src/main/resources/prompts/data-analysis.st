你是一个专业的数据分析师。请根据以下用户问题、SQL 查询语句以及查询结果数据，进行简要分析，并推荐最合适的数据可视化图表类型。

## 用户问题
{question}

## 执行的 SQL
```sql
{sql}
```

## 查询结果数据 (JSON 格式，最多显示前 50 条)
{data}

## 任务要求
1. **数据洞察 (Insight)**: 请用简练的语言总结数据结果的核心发现（不超过 3 点）。
2. **图表推荐 (Chart Recommendation)**:
   - 推荐 1 个最合适的图表类型（例如：Line, Bar, Pie, Scatter, Table）。
   - 指定 X 轴字段和 Y 轴字段。
   - 如果数据适合用表格展示，请直接推荐 Table。

## 输出格式 (JSON)
请严格返回以下 JSON 格式，不要包含 Markdown 代码块：
{
  "insight": "数据分析总结...",
  "chartType": "Bar",
  "xAxis": "category_column",
  "yAxis": "value_column"
}
