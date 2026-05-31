import java.sql.*;
public class InsertExamples {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM sql_example WHERE data_source_id = (SELECT id FROM data_source WHERE db_name = 'ecommerce_test')");
                
                long dsId = 4; // Will be determined dynamically
                ResultSet rs = s.executeQuery("SELECT id FROM data_source WHERE db_name = 'ecommerce_test'");
                if (rs.next()) dsId = rs.getLong(1);
                
                String[][] examples = {
                    {"查询每个部门有多少员工", "SELECT d.name AS department_name, COUNT(e.id) AS employee_count FROM departments d LEFT JOIN employees e ON d.id = e.department_id GROUP BY d.id, d.name ORDER BY employee_count DESC"},
                    {"查询销量前10的产品", "SELECT p.name AS product_name, SUM(oi.quantity) AS total_sales FROM products p JOIN order_items oi ON p.id = oi.product_id GROUP BY p.id, p.name ORDER BY total_sales DESC LIMIT 10"},
                    {"查询每个客户的订单数量和总消费", "SELECT c.name AS customer_name, COUNT(o.id) AS order_count, SUM(o.actual_amount) AS total_spent FROM customers c LEFT JOIN orders o ON c.id = o.customer_id GROUP BY c.id, c.name ORDER BY total_spent DESC"},
                    {"查询各仓库的库存总值", "SELECT w.name AS warehouse_name, SUM(i.quantity * p.unit_price) AS total_value FROM warehouses w JOIN inventory i ON w.id = i.warehouse_id JOIN products p ON i.product_id = p.id GROUP BY w.id, w.name ORDER BY total_value DESC"},
                    {"查询各产品分类下的产品数量", "SELECT pc.name AS category_name, COUNT(p.id) AS product_count FROM product_categories pc LEFT JOIN products p ON pc.id = p.category_id WHERE pc.level = 2 GROUP BY pc.id, pc.name ORDER BY product_count DESC"},
                    {"查询每个供应商的产品数量和平均价格", "SELECT s.name AS supplier_name, COUNT(p.id) AS product_count, AVG(p.unit_price) AS avg_price FROM suppliers s LEFT JOIN products p ON s.id = p.supplier_id GROUP BY s.id, s.name ORDER BY product_count DESC"},
                    {"查询每个月的订单数量和销售额", "SELECT TO_CHAR(o.ordered_at, 'YYYY-MM') AS month, COUNT(o.id) AS order_count, SUM(o.actual_amount) AS total_sales FROM orders o GROUP BY TO_CHAR(o.ordered_at, 'YYYY-MM') ORDER BY month"},
                    {"查询不同会员等级的客户数量和平均消费", "SELECT c.member_level, COUNT(c.id) AS customer_count, AVG(c.total_spent) AS avg_spent FROM customers c GROUP BY c.member_level ORDER BY avg_spent DESC"},
                    {"查询评价最高的10个产品", "SELECT p.name AS product_name, AVG(pr.rating) AS avg_rating, COUNT(pr.id) AS review_count FROM products p JOIN product_reviews pr ON p.id = pr.product_id GROUP BY p.id, p.name ORDER BY avg_rating DESC, review_count DESC LIMIT 10"},
                    {"查询各部门的平均薪资", "SELECT d.name AS department_name, AVG(e.salary) AS avg_salary FROM departments d JOIN employees e ON d.id = e.department_id GROUP BY d.id, d.name ORDER BY avg_salary DESC"}
                };
                
                PreparedStatement ps = c.prepareStatement("INSERT INTO sql_example (data_source_id, question, sql_query, description, is_active) VALUES (?, ?, ?, ?, 1)");
                for (String[] ex : examples) {
                    ps.setLong(1, dsId);
                    ps.setString(2, ex[0]);
                    ps.setString(3, ex[1]);
                    ps.setString(4, "E-commerce example: " + ex[0]);
                    ps.executeUpdate();
                }
                System.out.println("Inserted " + examples.length + " SQL examples for datasource " + dsId);
            }
        }
    }
}
