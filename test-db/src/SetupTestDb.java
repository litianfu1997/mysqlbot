import java.sql.*;
import java.time.LocalDateTime;
import java.time.LocalDate;

public class SetupTestDb {
    static final String HOST = "localhost";
    static final int PORT = 5432;
    static final String USER = "postgres";
    static final String PASSWORD = System.getenv().getOrDefault("PG_PASSWORD", "123456");
    static final String DB_NAME = "ecommerce_test";
    static final String[] SURNAMES = {"张","王","李","刘","陈","杨","赵","黄","周","吴","徐","孙","马","朱","胡","郭","林","何","高","罗","梁","宋","郑","谢","韩","唐","冯","董","萧","程","曹","袁","邓","许","傅","沈","曾","彭","吕","苏","卢","蒋","蔡","贾","丁","魏","薛","叶","阎","余"};
    static final String[] GIVEN = {"伟","芳","娜","洋","静","磊","敏","强","杰","秀英","明","丽","超","军","建华","志强","美玲","晓峰","峰","婷","思远","雪","鹏","冰","志豪","雅","大伟","然","晓","磊","媛","文","嘉","凡","勇","娟","莉","飞","涛","伟","雪","明","静雯","一","翔","峰","凡","明","波"};
    static final String[] POSITIONS = {"初级工程师","中级工程师","高级工程师","资深工程师","技术专家","专员","高级专员","主管","经理","高级经理","总监","副总裁","分析师","高级分析师","助理","实习生","顾问","项目经理","架构师"};
    static final int[] SALARIES = {8000,9000,10000,12000,15000,18000,20000,25000,30000,35000,40000,50000,60000,8000,8500,5000,30000,22000,45000};
    static final String[] MEMBER = {"regular","silver","gold","platinum","diamond"};
    static final String[] PREFIXES = {"138","139","136","137","135","158","159","150","151","152","153","155","156","157","182","185","186","187","188","189"};
    static final String[] STATUSES = {"pending","paid","shipped","delivered","cancelled","returned","delivered","delivered","delivered","paid"};
    static final String[] PAYMETHODS = {"alipay","wechat","credit_card","bank_transfer","cash_on_delivery","alipay","wechat","credit_card"};
    static final String[] CARRIERS = {"顺丰速运","中通快递","圆通速递","韵达快递","申通快递","京东物流","极兔速递"};
    static final String[] RTITLES = {"非常满意","好评","物超所值","推荐购买","质量不错","一般般","性价比高","还行","物流很快","包装完好"};
    static final String[] RCONTENTS = {"产品质量非常好，做工精细，值得购买。已经回购很多次了。","发货速度快，包装仔细，商品完好无损，五星好评！","性价比很高，比实体店便宜不少，质量也很好。","用了一段时间才来评价，确实不错，推荐给大家。","这个价格买到这样的质量，非常满意。","整体还行，但是有些小瑕疵，希望改进。","物流很给力，两天就到了，产品也不错。","买给朋友的，朋友说很好用，我也打算买一个。","质量一般，没有预期那么好，但也不算差。","客服态度很好，解答很耐心，产品也还行。"};
    static final int[] RATINGS = {5,5,5,4,4,4,4,3,3,2};

    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        System.out.println(">>> Creating database " + DB_NAME + " ...");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://"+HOST+":"+PORT+"/postgres", USER, PASSWORD)) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT 1 FROM pg_database WHERE datname='"+DB_NAME+"'");
                if (rs.next()) { System.out.println("    Dropping existing database..."); s.execute("DROP DATABASE "+DB_NAME); }
                s.execute("CREATE DATABASE "+DB_NAME+" ENCODING 'UTF8'");
            }
        }
        System.out.println("    Database created.");
        String url = "jdbc:postgresql://"+HOST+":"+PORT+"/"+DB_NAME;
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD)) {
            conn.setAutoCommit(false);
            System.out.println(">>> Creating tables..."); createTables(conn); conn.commit();
            System.out.println(">>> Inserting regions..."); insertRegions(conn); conn.commit();
            System.out.println(">>> Inserting departments..."); insertDepartments(conn); conn.commit();
            System.out.println(">>> Inserting employees (200)..."); insertEmployees(conn); conn.commit();
            System.out.println(">>> Inserting warehouses (5)..."); insertWarehouses(conn); conn.commit();
            System.out.println(">>> Inserting suppliers (30)..."); insertSuppliers(conn); conn.commit();
            System.out.println(">>> Inserting categories..."); insertCategories(conn); conn.commit();
            System.out.println(">>> Inserting products (500)..."); insertProducts(conn); conn.commit();
            System.out.println(">>> Inserting customers (1000)..."); insertCustomers(conn); conn.commit();
            System.out.println(">>> Inserting orders (5000)..."); insertOrders(conn); conn.commit();
            System.out.println(">>> Inserting order items..."); insertOrderItems(conn); conn.commit();
            System.out.println(">>> Inserting payments..."); insertPayments(conn); conn.commit();
            System.out.println(">>> Inserting inventory..."); insertInventory(conn); conn.commit();
            System.out.println(">>> Inserting reviews (3000)..."); insertReviews(conn); conn.commit();
            System.out.println(">>> Inserting shipping..."); insertShipping(conn); conn.commit();
            System.out.println(">>> Creating indexes..."); createIndexes(conn); conn.commit();
            try (Statement s = conn.createStatement()) { s.execute("ANALYZE"); } conn.commit();
            printSummary(conn);
        }
        System.out.println("\n>>> Done! Connect: jdbc:postgresql://"+HOST+":"+PORT+"/"+DB_NAME);
    }

    static void createTables(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS shipping_records, product_reviews, payment_records, order_items, orders, inventory, products, product_categories, suppliers, customers, employees, warehouses, departments, regions CASCADE");
            s.execute("CREATE TABLE regions (id SERIAL PRIMARY KEY, name VARCHAR(50) NOT NULL, level SMALLINT NOT NULL, parent_id INT REFERENCES regions(id), created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE regions IS '区域表'");
            s.execute("CREATE TABLE departments (id SERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, parent_id INT REFERENCES departments(id), manager_id INT, budget DECIMAL(15,2), created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE departments IS '部门表'");
            s.execute("CREATE TABLE employees (id SERIAL PRIMARY KEY, name VARCHAR(50) NOT NULL, email VARCHAR(100) NOT NULL UNIQUE, phone VARCHAR(20), department_id INT NOT NULL REFERENCES departments(id), position VARCHAR(100), salary DECIMAL(10,2), hire_date DATE NOT NULL, status SMALLINT DEFAULT 1, manager_id INT REFERENCES employees(id), created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE employees IS '员工表'");
            s.execute("CREATE TABLE warehouses (id SERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, region_id INT REFERENCES regions(id), address VARCHAR(300), capacity INT, manager_id INT REFERENCES employees(id), status SMALLINT DEFAULT 1, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE warehouses IS '仓库表'");
            s.execute("CREATE TABLE suppliers (id SERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, contact_person VARCHAR(50), phone VARCHAR(20), email VARCHAR(100), region_id INT REFERENCES regions(id), address VARCHAR(300), rating DECIMAL(3,2), cooperation_start_date DATE, status SMALLINT DEFAULT 1, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE suppliers IS '供应商表'");
            s.execute("CREATE TABLE product_categories (id SERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, parent_id INT REFERENCES product_categories(id), level SMALLINT NOT NULL, sort_order INT DEFAULT 0, is_active SMALLINT DEFAULT 1, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE product_categories IS '产品分类表'");
            s.execute("CREATE TABLE products (id SERIAL PRIMARY KEY, name VARCHAR(200) NOT NULL, category_id INT NOT NULL REFERENCES product_categories(id), supplier_id INT REFERENCES suppliers(id), sku VARCHAR(50) NOT NULL UNIQUE, unit_price DECIMAL(10,2) NOT NULL, cost_price DECIMAL(10,2), brand VARCHAR(100), weight_kg DECIMAL(8,3), description TEXT, status SMALLINT DEFAULT 1, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE products IS '产品表'");
            s.execute("CREATE TABLE customers (id SERIAL PRIMARY KEY, name VARCHAR(50) NOT NULL, email VARCHAR(100) UNIQUE, phone VARCHAR(20), gender CHAR(1), age INT, region_id INT REFERENCES regions(id), address VARCHAR(300), member_level VARCHAR(20) DEFAULT 'regular', total_spent DECIMAL(12,2) DEFAULT 0, order_count INT DEFAULT 0, registered_at TIMESTAMP DEFAULT NOW(), last_login_at TIMESTAMP, status SMALLINT DEFAULT 1)");
            s.execute("COMMENT ON TABLE customers IS '客户表'");
            s.execute("CREATE TABLE orders (id SERIAL PRIMARY KEY, order_no VARCHAR(32) NOT NULL UNIQUE, customer_id INT NOT NULL REFERENCES customers(id), employee_id INT REFERENCES employees(id), warehouse_id INT REFERENCES warehouses(id), order_status VARCHAR(20) NOT NULL, payment_method VARCHAR(30), total_amount DECIMAL(12,2) NOT NULL, discount_amount DECIMAL(12,2) DEFAULT 0, shipping_fee DECIMAL(8,2) DEFAULT 0, actual_amount DECIMAL(12,2) NOT NULL, region_id INT REFERENCES regions(id), shipping_address VARCHAR(300), remark TEXT, ordered_at TIMESTAMP NOT NULL, paid_at TIMESTAMP, shipped_at TIMESTAMP, delivered_at TIMESTAMP, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE orders IS '订单表'");
            s.execute("CREATE TABLE order_items (id SERIAL PRIMARY KEY, order_id INT NOT NULL REFERENCES orders(id), product_id INT NOT NULL REFERENCES products(id), quantity INT NOT NULL, unit_price DECIMAL(10,2) NOT NULL, subtotal DECIMAL(12,2) NOT NULL, discount_rate DECIMAL(3,2) DEFAULT 0, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE order_items IS '订单明细表'");
            s.execute("CREATE TABLE payment_records (id SERIAL PRIMARY KEY, order_id INT NOT NULL REFERENCES orders(id), payment_no VARCHAR(64) NOT NULL UNIQUE, payment_method VARCHAR(30) NOT NULL, amount DECIMAL(12,2) NOT NULL, status VARCHAR(20) NOT NULL, paid_at TIMESTAMP, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE payment_records IS '支付记录表'");
            s.execute("CREATE TABLE inventory (id SERIAL PRIMARY KEY, product_id INT NOT NULL REFERENCES products(id), warehouse_id INT NOT NULL REFERENCES warehouses(id), quantity INT NOT NULL DEFAULT 0, safety_stock INT DEFAULT 50, last_restock_at TIMESTAMP, created_at TIMESTAMP DEFAULT NOW(), updated_at TIMESTAMP DEFAULT NOW(), UNIQUE(product_id, warehouse_id))");
            s.execute("COMMENT ON TABLE inventory IS '库存表'");
            s.execute("CREATE TABLE product_reviews (id SERIAL PRIMARY KEY, product_id INT NOT NULL REFERENCES products(id), customer_id INT NOT NULL REFERENCES customers(id), order_id INT NOT NULL REFERENCES orders(id), rating SMALLINT NOT NULL, title VARCHAR(200), content TEXT, is_anonymous SMALLINT DEFAULT 0, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE product_reviews IS '商品评价表'");
            s.execute("CREATE TABLE shipping_records (id SERIAL PRIMARY KEY, order_id INT NOT NULL REFERENCES orders(id), carrier VARCHAR(50) NOT NULL, tracking_no VARCHAR(64) NOT NULL UNIQUE, status VARCHAR(20) NOT NULL, shipped_at TIMESTAMP, delivered_at TIMESTAMP, created_at TIMESTAMP DEFAULT NOW())");
            s.execute("COMMENT ON TABLE shipping_records IS '物流记录表'");
        }
    }

    static void insertRegions(Connection conn) throws SQLException {
        String[] provs = {"北京市","上海市","广东省","浙江省","江苏省","四川省","湖北省","山东省","福建省","河南省"};
        String[][] cities = {{},{},{"广州市","深圳市","东莞市","佛山市"},{"杭州市","宁波市","温州市","嘉兴市"},{"南京市","苏州市","无锡市","常州市"},{"成都市","绵阳市"},{"武汉市","宜昌市"},{"济南市","青岛市","烟台市"},{"福州市","厦门市"},{"郑州市","洛阳市"}};
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO regions (name,level,parent_id) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            int[] pids = new int[provs.length];
            for (int i=0;i<provs.length;i++) { ps.setString(1,provs[i]); ps.setInt(2,1); ps.setNull(3,Types.INTEGER); ps.executeUpdate(); try(ResultSet rs=ps.getGeneratedKeys()){rs.next();pids[i]=rs.getInt(1);} }
            for (int i=0;i<cities.length;i++) for (String c:cities[i]) { ps.setString(1,c); ps.setInt(2,2); ps.setInt(3,pids[i]); ps.executeUpdate(); }
        }
    }

    static void insertDepartments(Connection conn) throws SQLException {
        Object[][] d = {{1,"总裁办",null},{2,"技术部",1},{3,"市场部",1},{4,"销售部",1},{5,"人力资源部",1},{6,"财务部",1},{7,"供应链管理部",1},{8,"客服部",1},{9,"后端开发组",2},{10,"前端开发组",2},{11,"数据分析组",2},{12,"线上营销组",3},{13,"品牌推广组",3},{14,"国内销售组",4},{15,"海外销售组",4},{16,"华东大区",14},{17,"华南大区",14},{18,"采购组",7},{19,"仓储物流组",7}};
        double[] b = {5e6,8e6,6e6,1e7,3e6,2.5e6,7e6,4e6,3e6,2.5e6,2.5e6,3e6,3e6,5e6,5e6,2.5e6,2.5e6,3.5e6,3.5e6};
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO departments (id,name,parent_id,budget) VALUES (?,?,?,?)")) {
            for (int i=0;i<d.length;i++) { ps.setInt(1,(int)d[i][0]); ps.setString(2,(String)d[i][1]); if(d[i][2]!=null) ps.setInt(3,(int)d[i][2]); else ps.setNull(3,Types.INTEGER); ps.setDouble(4,b[i]); ps.executeUpdate(); }
        }
    }

    static void insertEmployees(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO employees (name,email,phone,department_id,position,salary,hire_date,status,manager_id) VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (int i=1;i<=200;i++) {
                String n = SURNAMES[(i-1)%SURNAMES.length]+GIVEN[(i-1)%GIVEN.length]+(i>100?(i-1)/100:"");
                ps.setString(1,n); ps.setString(2,"emp"+i+"@company.com");
                ps.setString(3,"1"+String.format("%010d",(i*137L+1000)%10000000000L));
                ps.setInt(4,(i%19)+1);
                ps.setString(5,POSITIONS[(i-1)%POSITIONS.length]);
                ps.setDouble(6,SALARIES[(i-1)%SALARIES.length]+i%3000);
                ps.setDate(7,java.sql.Date.valueOf(LocalDate.now().minusDays((i*17+100)%2000+i%365)));
                ps.setInt(8,i%20==0?0:1);
                if(i<=19) ps.setNull(9,Types.INTEGER); else ps.setInt(9,(i%19)+1);
                ps.executeUpdate();
            }
        }
    }

    static void insertWarehouses(Connection conn) throws SQLException {
        Object[][] w = {{1,"北京中央仓",1,"北京市大兴区物流园A区1号",100000,5},{2,"上海华东仓",2,"上海市嘉定区仓储中心B栋",80000,15},{3,"广州华南仓",3,"广州市白云区物流港C座",90000,25},{4,"成都西南仓",6,"成都市双流区产业园D区",60000,45},{5,"武汉华中仓",7,"武汉市东西湖区物流基地E栋",70000,65}};
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO warehouses (id,name,region_id,address,capacity,manager_id) VALUES (?,?,?,?,?,?)")) {
            for (Object[] r:w) { ps.setInt(1,(int)r[0]); ps.setString(2,(String)r[1]); ps.setInt(3,(int)r[2]); ps.setString(4,(String)r[3]); ps.setInt(5,(int)r[4]); ps.setInt(6,(int)r[5]); ps.executeUpdate(); }
        }
    }

    static void insertSuppliers(Connection conn) throws SQLException {
        String[] nm = {"华为供应链","小米科技供应商","联想集团采购部","戴尔中国供应商","苹果配件供应商","三星电子供应商","索尼中国代理","松下电器供应","飞利浦中国供应","博世工具供应商","美的集团供应链","格力电器供应商","海尔智家供应部","TCL科技供应商","海信集团供应商","创维数字供应商","优衣库中国供应","耐克中国代理","阿迪达斯供应商","李宁体育供应商","安踏体育供应","鸿星尔克供应","比亚迪供应链","宁德时代供应商","隆基绿能供应","通威股份供应商","三一重工供应","中联重科供应商","徐工机械供应商","潍柴动力供应"};
        String[] ct = {"张经理","王总","李主任","刘经理","陈总","杨经理","赵总","黄经理","周总","吴经理","徐总","孙经理","马总","朱经理","胡总","郭经理","林总","何经理","高总","罗经理","梁总","宋经理","郑总","谢经理","韩总","唐经理","冯总","董经理","萧总","程经理"};
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO suppliers (name,contact_person,phone,email,region_id,address,rating,cooperation_start_date,status) VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (int i=1;i<=30;i++) {
                ps.setString(1,nm[(i-1)%nm.length]); ps.setString(2,ct[(i-1)%ct.length]);
                ps.setString(3,"400-"+String.format("%08d",(i*7919)%100000000));
                ps.setString(4,"contact@supplier"+i+".com"); ps.setInt(5,(i%10)+1);
                ps.setString(6,"供应商地址"+i+"号"); ps.setDouble(7,3.5+(i%15)*0.1);
                ps.setDate(8,java.sql.Date.valueOf(LocalDate.now().minusDays((i*73)%1500)));
                ps.setInt(9,i%15==0?0:1); ps.executeUpdate();
            }
        }
    }

    static void insertCategories(Connection conn) throws SQLException {
        Object[][] c = {
            {1,"电子产品",null,1},{2,"服装鞋帽",null,2},{3,"家居生活",null,3},{4,"食品饮料",null,4},{5,"运动户外",null,5},{6,"美妆个护",null,6},{7,"图书文具",null,7},{8,"母婴用品",null,8},
            {11,"手机通讯",1,2},{12,"电脑办公",1,2},{13,"智能穿戴",1,2},{14,"影音设备",1,2},{15,"男装",2,2},{16,"女装",2,2},{17,"童装",2,2},{18,"鞋靴",2,2},{19,"家具",3,2},{20,"厨具",3,2},{21,"家纺",3,2},{22,"收纳整理",3,2},{23,"零食",4,2},{24,"饮料",4,2},{25,"生鲜",4,2},{26,"运动服饰",5,2},{27,"运动器材",5,2},{28,"户外装备",5,2},{29,"护肤",6,2},{30,"彩妆",6,2},{31,"图书",7,2},{32,"文具",7,2},{33,"奶粉辅食",8,2},{34,"童车童床",8,2},
            {101,"智能手机",11,3},{102,"功能手机",11,3},{103,"笔记本电脑",12,3},{104,"台式电脑",12,3},{105,"平板电脑",12,3},{106,"智能手表",13,3},{107,"智能手环",13,3},{108,"耳机",14,3},{109,"音响",14,3},{110,"T恤",15,3},{111,"衬衫",15,3},{112,"连衣裙",16,3},{113,"半身裙",16,3},{114,"沙发",19,3},{115,"床",19,3},{116,"炒锅",20,3},{117,"刀具",20,3},{118,"坚果",23,3},{119,"饼干糕点",23,3},{120,"跑步鞋",26,3},{121,"瑜伽服",26,3},{122,"跑步机",27,3},{123,"哑铃",27,3},{124,"帐篷",28,3},{125,"登山杖",28,3},{126,"面膜",29,3},{127,"防晒霜",29,3},{128,"口红",30,3},{129,"粉底",30,3},{130,"编程图书",31,3},{131,"文学图书",31,3},{132,"钢笔",32,3},{133,"笔记本",32,3}
        };
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO product_categories (id,name,parent_id,level,sort_order) VALUES (?,?,?,?,?)")) {
            for (Object[] r:c) { ps.setInt(1,(int)r[0]); ps.setString(2,(String)r[1]); if(r[2]!=null) ps.setInt(3,(int)r[2]); else ps.setNull(3,Types.INTEGER); ps.setInt(4,(int)r[3]); ps.setInt(5,(int)r[0]); ps.executeUpdate(); }
        }
    }

    static void insertProducts(Connection conn) throws SQLException {
        String[] pn = {"iPhone 15 Pro Max","iPhone 15","iPhone 14","华为Mate 60 Pro","华为P60","小米14 Ultra","小米14","OPPO Find X7","vivo X100 Pro","荣耀Magic6","三星Galaxy S24","一加12","MacBook Pro 16寸","MacBook Air M3","ThinkPad X1 Carbon","Dell XPS 15","华为MateBook X Pro","联想小新Pro 16","华硕天选4","惠普暗影精灵9","iPad Pro 12.9","iPad Air","华为MatePad Pro","小米平板6","Apple Watch Ultra 2","Apple Watch S9","华为Watch GT4","小米手环8 Pro","AirPods Pro 2","索尼WH-1000XM5","华为FreeBuds Pro 3","BOSE QC45","JBL Charge 5","Marshall Stanmore III","华为Sound Joy","海澜之家男士T恤","优衣库纯棉T恤","Nike Dri-FIT T恤","Adidas跑步T恤","太平鸟男士衬衫","雅戈尔商务衬衫","杉杉免烫衬衫","ZARA连衣裙","优衣库连衣裙","VERO MODA连衣裙","ONLY连衣裙","林氏木业沙发","全友家居沙发","芝华仕头等舱沙发","顾家家居沙发","宜家MALM床架","林氏木业床","全友家居床","苏泊尔炒锅","美的不粘锅","九阳炒锅","炊大皇铁锅","双立人刀具套装","十八子作菜刀","张小泉剪刀","三只松鼠坚果大礼包","百草味每日坚果","良品铺子坚果","洽洽小黄袋","奥利奥饼干","徐福记糕点","好利来蛋糕","达利园面包","Nike Air Max 270","Adidas Ultraboost","New Balance 990","亚瑟士GEL-KAYANO","李宁赤兔6 Pro","安踏C202 GT","特步160X","Lululemon瑜伽裤","Keep瑜伽服","迪卡侬瑜伽服","舒华跑步机","Keep跑步机","亿健跑步机","力健哑铃套装","Keep哑铃","迪卡侬哑铃","牧高笛帐篷","骆驼帐篷","Naturehike帐篷","黑钻登山杖","开拓者登山杖","LEKI登山杖","SK-II面膜","雅诗兰黛面膜","兰蔻面膜","欧莱雅面膜","安耐晒防晒霜","碧柔防晒霜","兰蔻防晒霜","MAC口红","YSL口红","迪奥口红","阿玛尼口红","花西子口红","雅诗兰黛粉底液","兰蔻粉底液","阿玛尼粉底液","MAC粉底","《深入理解计算机系统》","《算法导论》","《设计模式》","《代码整洁之道》","《活着》","《三体》","《百年孤独》","《围城》","万宝龙钢笔","派克钢笔","英雄钢笔","凌美钢笔","Moleskine笔记本","晨光笔记本","得力笔记本","国誉笔记本"};
        int[] pc = {101,101,101,101,101,101,101,101,101,101,101,101,103,103,103,103,103,103,103,103,105,105,105,105,106,106,106,107,108,108,108,108,109,109,109,110,110,110,110,111,111,111,112,112,112,112,114,114,114,114,115,115,115,116,116,116,116,117,117,117,118,118,118,118,119,119,119,119,120,120,120,120,120,120,120,121,121,121,122,122,122,123,123,123,124,124,124,125,125,125,126,126,126,126,127,127,127,128,128,128,128,128,129,129,129,129,130,130,130,130,131,131,131,131,132,132,132,132,133,133,133,133};
        String[] br = {"Apple","Apple","Apple","华为","华为","小米","小米","OPPO","vivo","荣耀","三星","一加","Apple","Apple","联想","Dell","华为","联想","华硕","惠普","Apple","Apple","华为","小米","Apple","Apple","华为","小米","Apple","索尼","华为","BOSE","JBL","Marshall","华为","海澜之家","优衣库","Nike","Adidas","太平鸟","雅戈尔","杉杉","ZARA","优衣库","VERO MODA","ONLY","林氏木业","全友家居","芝华仕","顾家家居","宜家","林氏木业","全友家居","苏泊尔","美的","九阳","炊大皇","双立人","十八子作","张小泉","三只松鼠","百草味","良品铺子","洽洽","奥利奥","徐福记","好利来","达利园","Nike","Adidas","New Balance","亚瑟士","李宁","安踏","特步","Lululemon","Keep","迪卡侬","舒华","Keep","亿健","力健","Keep","迪卡侬","牧高笛","骆驼","Naturehike","黑钻","开拓者","LEKI","SK-II","雅诗兰黛","兰蔻","欧莱雅","安耐晒","碧柔","兰蔻","MAC","YSL","迪奥","阿玛尼","花西子","雅诗兰黛","兰蔻","阿玛尼","MAC","人民邮电出版社","人民邮电出版社","机械工业出版社","人民邮电出版社","作家出版社","重庆出版社","南海出版公司","人民文学出版社","万宝龙","派克","英雄","凌美","Moleskine","晨光","得力","国誉"};
        double[] pr = {3000,3000,3000,3000,3000,3000,3000,3000,3000,3000,3000,3000,5000,5000,5000,5000,5000,5000,5000,5000,2000,2000,2000,2000,500,500,500,300,300,300,300,300,500,500,500,80,80,80,80,150,150,150,200,200,200,200,2000,2000,2000,2000,1000,1000,1000,80,80,80,80,50,50,50,30,30,30,30,15,15,15,15,500,500,500,500,500,500,500,200,200,200,2000,2000,2000,50,50,50,100,100,100,200,200,200,100,100,100,100,80,80,80,200,200,200,200,200,150,150,150,150,60,60,60,60,40,40,40,40,100,100,100,100,20,20,20,20};
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO products (name,category_id,supplier_id,sku,unit_price,cost_price,brand,weight_kg,description,status) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (int i=1;i<=500;i++) {
                int idx=(i-1)%pn.length;
                ps.setString(1,pn[idx]+(i>pn.length?" "+((i-1)/pn.length+1)+"代":""));
                ps.setInt(2,pc[idx]); ps.setInt(3,(i%30)+1);
                ps.setString(4,"SKU-"+String.format("%06d",i));
                double bp=pr[idx]+(i%20)*(pr[idx]/20);
                ps.setDouble(5,bp); ps.setDouble(6,bp*(0.5+(i%20)*0.01));
                ps.setString(7,br[idx]); ps.setDouble(8,0.05+(i%500)*0.01);
                ps.setString(9,"优质商品，品质保证。SKU编号: SKU-"+String.format("%06d",i));
                ps.setInt(10,i%30==0?0:1); ps.executeUpdate();
            }
        }
    }

    static void insertCustomers(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO customers (name,email,phone,gender,age,region_id,address,member_level,total_spent,order_count,registered_at,last_login_at,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i=1;i<=1000;i++) {
                String n=SURNAMES[(i-1)%SURNAMES.length]+GIVEN[(i-1)%GIVEN.length]+(i>100?(i-1)/100:"");
                ps.setString(1,n); ps.setString(2,"user"+i+"@example.com");
                ps.setString(3,PREFIXES[i%PREFIXES.length]+String.format("%08d",(i*137L+2000)%100000000));
                ps.setString(4,i%2==0?"M":"F"); ps.setInt(5,18+i%50); ps.setInt(6,(i%10)+1);
                ps.setString(7,"客户地址"+i+"号"); ps.setString(8,MEMBER[i%MEMBER.length]);
                ps.setDouble(9,(i%100)*150+200); ps.setInt(10,i%20);
                ps.setTimestamp(11,Timestamp.valueOf(LocalDateTime.now().minusDays(i%730)));
                ps.setTimestamp(12,Timestamp.valueOf(LocalDateTime.now().minusDays(i%30)));
                ps.setInt(13,i%50==0?0:1); ps.executeUpdate();
            }
        }
    }

    static void insertOrders(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO orders (order_no,customer_id,employee_id,warehouse_id,order_status,payment_method,total_amount,discount_amount,shipping_fee,actual_amount,region_id,shipping_address,remark,ordered_at,paid_at,shipped_at,delivered_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i=1;i<=5000;i++) {
                String st=STATUSES[i%STATUSES.length];
                double ta=(i%200)*50+100, disc=i%5==0?(i%200)*5+10:0, ship=ta>200?0:10, act=ta-disc-ship;
                LocalDateTime od=LocalDateTime.now().minusDays(1000+(i*37)%730).plusHours(i%24);
                ps.setString(1,"ORD-"+String.format("%08d",i)); ps.setInt(2,(i%1000)+1);
                if(i%3==0) ps.setInt(3,(i%200)+1); else ps.setNull(3,Types.INTEGER);
                ps.setInt(4,(i%5)+1); ps.setString(5,st); ps.setString(6,PAYMETHODS[i%PAYMETHODS.length]);
                ps.setDouble(7,ta); ps.setDouble(8,disc); ps.setDouble(9,ship); ps.setDouble(10,act);
                ps.setInt(11,(i*7%10)+1); ps.setString(12,"收货地址"+i+"号");
                ps.setString(13,i%10==0?"请尽快发货":(i%10==1?"周末送达":null));
                ps.setTimestamp(14,Timestamp.valueOf(od));
                if(!st.equals("pending")) ps.setTimestamp(15,Timestamp.valueOf(od.plusHours(1+i%24))); else ps.setNull(15,Types.TIMESTAMP);
                if(st.equals("shipped")||st.equals("delivered")||st.equals("returned")) ps.setTimestamp(16,Timestamp.valueOf(od.plusHours(24+i%24))); else ps.setNull(16,Types.TIMESTAMP);
                if(st.equals("delivered")||st.equals("returned")) ps.setTimestamp(17,Timestamp.valueOf(od.plusHours(72+i%24))); else ps.setNull(17,Types.TIMESTAMP);
                ps.executeUpdate();
            }
        }
    }

    static void insertOrderItems(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO order_items (order_id,product_id,quantity,unit_price,subtotal,discount_rate) VALUES (?,?,?,?,?,?)")) {
            for (int oid=1;oid<=5000;oid++) {
                int cnt=(oid%3)+1;
                for (int seq=1;seq<=cnt;seq++) {
                    int pid=((oid*13+seq*7)%500)+1, qty=(seq*37+oid*13)%5+1;
                    double up=100;
                    try (PreparedStatement p2=conn.prepareStatement("SELECT unit_price FROM products WHERE id=?")) { p2.setInt(1,pid); try(ResultSet rs=p2.executeQuery()){if(rs.next()) up=rs.getDouble(1);} }
                    ps.setInt(1,oid); ps.setInt(2,pid); ps.setInt(3,qty); ps.setDouble(4,up); ps.setDouble(5,qty*up); ps.setDouble(6,oid%10==0?0.05:0); ps.executeUpdate();
                }
            }
        }
    }

    static void insertPayments(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO payment_records (order_id,payment_no,payment_method,amount,status,paid_at) VALUES (?,?,?,?,?,?)")) {
            for (int i=1;i<=5000;i++) {
                String st=STATUSES[i%STATUSES.length];
                if(st.equals("pending")&&i%10!=0) continue;
                double amt=0; String m="alipay"; java.sql.Timestamp pt=null;
                try (PreparedStatement p2=conn.prepareStatement("SELECT actual_amount,payment_method,paid_at FROM orders WHERE id=?")) { p2.setInt(1,i); try(ResultSet rs=p2.executeQuery()){if(rs.next()){amt=rs.getDouble(1);m=rs.getString(2);pt=rs.getTimestamp(3);}} }
                ps.setInt(1,i); ps.setString(2,"PAY-"+String.format("%010d",i));
                ps.setString(3,m!=null?m:"alipay"); ps.setDouble(4,amt);
                ps.setString(5,st.equals("cancelled")?"refunded":(st.equals("pending")?"pending":"completed"));
                ps.setTimestamp(6,pt); ps.executeUpdate();
            }
        }
    }

    static void insertInventory(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO inventory (product_id,warehouse_id,quantity,safety_stock,last_restock_at) VALUES (?,?,?,?,?)")) {
            for (int pid=1;pid<=300;pid++) {
                for (int wid=1;wid<=5;wid++) {
                    int qty=(pid*17+wid*53)%2000+10, ss=100;
                    try (PreparedStatement p2=conn.prepareStatement("SELECT unit_price FROM products WHERE id=?")) { p2.setInt(1,pid); try(ResultSet rs=p2.executeQuery()){if(rs.next()){double p=rs.getDouble(1);if(p>2000)ss=20;else if(p>500)ss=50;}} }
                    ps.setInt(1,pid); ps.setInt(2,wid); ps.setInt(3,qty); ps.setInt(4,ss);
                    ps.setTimestamp(5,Timestamp.valueOf(LocalDateTime.now().minusDays((pid+wid)%90))); ps.executeUpdate();
                }
            }
        }
    }

    static void insertReviews(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO product_reviews (product_id,customer_id,order_id,rating,title,content,is_anonymous,created_at) VALUES (?,?,?,?,?,?,?,?)")) {
            for (int i=1;i<=3000;i++) {
                int idx=(i-1)%10;
                ps.setInt(1,(i%500)+1); ps.setInt(2,(i*7%1000)+1); ps.setInt(3,(i*3%5000)+1);
                ps.setInt(4,RATINGS[idx]); ps.setString(5,RTITLES[idx]); ps.setString(6,RCONTENTS[idx]);
                ps.setInt(7,i%5==0?1:0); ps.setTimestamp(8,Timestamp.valueOf(LocalDateTime.now().minusDays(i%365).plusHours(i%24))); ps.executeUpdate();
            }
        }
    }

    static void insertShipping(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO shipping_records (order_id,carrier,tracking_no,status,shipped_at,delivered_at) VALUES (?,?,?,?,?,?)")) {
            for (int i=1;i<=5000;i++) {
                String st=STATUSES[i%STATUSES.length];
                if(!st.equals("shipped")&&!st.equals("delivered")&&!st.equals("returned")) continue;
                java.sql.Timestamp sa=null,da=null;
                try (PreparedStatement p2=conn.prepareStatement("SELECT shipped_at,delivered_at FROM orders WHERE id=?")) { p2.setInt(1,i); try(ResultSet rs=p2.executeQuery()){if(rs.next()){sa=rs.getTimestamp(1);da=rs.getTimestamp(2);}} }
                ps.setInt(1,i); ps.setString(2,CARRIERS[i%CARRIERS.length]); ps.setString(3,"SF"+String.format("%012d",i));
                ps.setString(4,st.equals("delivered")?"delivered":(st.equals("shipped")?"in_transit":"returned"));
                ps.setTimestamp(5,sa); ps.setTimestamp(6,da); ps.executeUpdate();
            }
        }
    }

    static void createIndexes(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE INDEX idx_orders_customer_id ON orders (customer_id)");
            s.execute("CREATE INDEX idx_orders_ordered_at ON orders (ordered_at)");
            s.execute("CREATE INDEX idx_orders_status ON orders (order_status)");
            s.execute("CREATE INDEX idx_order_items_order_id ON order_items (order_id)");
            s.execute("CREATE INDEX idx_order_items_product_id ON order_items (product_id)");
            s.execute("CREATE INDEX idx_products_category_id ON products (category_id)");
            s.execute("CREATE INDEX idx_products_supplier_id ON products (supplier_id)");
            s.execute("CREATE INDEX idx_employees_department_id ON employees (department_id)");
            s.execute("CREATE INDEX idx_inventory_product_id ON inventory (product_id)");
            s.execute("CREATE INDEX idx_customers_member_level ON customers (member_level)");
            s.execute("CREATE INDEX idx_customers_registered_at ON customers (registered_at)");
            s.execute("CREATE INDEX idx_reviews_product_id ON product_reviews (product_id)");
        }
    }

    static void printSummary(Connection conn) throws SQLException {
        String[] tables = {"regions","departments","employees","warehouses","suppliers","product_categories","products","customers","orders","order_items","payment_records","inventory","product_reviews","shipping_records"};
        System.out.println("\n========== 数据统计 ==========");
        for (String t:tables) { try(Statement s=conn.createStatement();ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM "+t)){rs.next();System.out.printf("  %-20s %,8d rows%n",t,rs.getInt(1));} }
        System.out.println("================================");
    }
}
