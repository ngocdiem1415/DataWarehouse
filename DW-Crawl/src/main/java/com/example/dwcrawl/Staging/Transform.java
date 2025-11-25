package com.example.dwcrawl.Staging;

import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class Transform {
    private String DB_STAGING;
    private String DB_CONTROL;
    private String USER;
    private String PASSWORD;
    private static final String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    //    private static final String errorFilePath = "D:/DataWarehouse/DW-Crawl/data/";
    private static final String errorFilePath = "/DW/control/config_error/";
    private String config_file_path;

    public String formatString(String s) {
        s = StringUtils.stripAccents(s);
        s = s.replace("Đ", "D").replace("đ", "d");
        s = s.replaceAll("[^a-zA-Z0-9]", "");
        return s;
    }

    private static String getRequiredProperty(Properties props, String key, String name) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new RuntimeException("Giá trị config " + name + " không được null hoặc rỗng");
        }
        return value;
    }

    public Connection connectToStaging() {
        try {
            Connection conn = DriverManager.getConnection(DB_STAGING, USER, PASSWORD);
            System.out.println("Connected to MySQL successfully!");
            return conn;
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            return null;
        }
    }

    public Connection connectToControl() {
        try {
            Connection conn = DriverManager.getConnection(DB_CONTROL, USER, PASSWORD);
            System.out.println("Connected to MySQL successfully!");
            return conn;
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            return null;
        }
    }

    public void loadConfig() {
//        File configFile = new File("D:/DataWarehouse/DW-Crawl/config.xml");
        File configFile = new File(config_file_path);

        // Xuất file ddMMyyyy_source-crawl_config-error.txt
        // trong thư mục /DW/control/config_error
        // báo lỗi ko tìm thấy config.xml
        if (!configFile.exists()) {
            File errorFile = new File(errorFilePath + currentDate + "_load-source_" + "config-error.txt");
            try (FileWriter writer = new FileWriter(errorFile)) {
                writer.write("Ko tìm thấy file config.xml");
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không tìm thấy file config.xml");
        }
        try (InputStream input = new FileInputStream(configFile)) {
            java.util.Properties props = new java.util.Properties();
            props.loadFromXML(input);

            // 2. Gán các giá trị cần thiết từ file config
            DB_STAGING = getRequiredProperty(props, "db_staging", "DB_STAGING");
            DB_CONTROL = getRequiredProperty(props, "db_control", "DB_CONTROL");
            USER = getRequiredProperty(props, "db_user_root_name", "USER");
            PASSWORD = getRequiredProperty(props, "db_user_root_pass", "PASSWORD");
        }

        //Xuất file
        //ddMMyyyy_source-crawl_loadConfigValue-error.txt
        //trong thư mục /DW/control/config_error
        //báo lỗi gán giá trị từ config không thành công
        catch (Exception e) {
            File error_loadConfig_value = new File(errorFilePath + currentDate + "_source-crawl_loadConfigValue-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(error_loadConfig_value))) {
                writer.write("Không thể load giá trị trong config.xml");
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Lỗi load config.xml: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) throws SQLException {
        Transform clean = new Transform();

        // 1. Load file config.xml
        for (String arg : args) { // Input: config_file_path
            if (arg.startsWith("config_file_path=")) {
                clean.config_file_path = arg.substring("config_file_path=".length()).trim();
                System.out.println("Using config file: " + clean.config_file_path);
            }
        }
        clean.loadConfig();

        // 3. Kết nối tới database
        Connection conn1 = clean.connectToStaging();
        Connection conn2 = clean.connectToControl();

        //Xuất file
        //ddMMyyyy_source-crawl_connectDB-error.txt
        //trong thư mục /DW/control/config_error
        //báo lỗi báo lỗi ko thể kết nối đến DB
        if (conn1 == null) {
            File errorConnectDB = new File(errorFilePath + currentDate + "_source-crawl_connectDB-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(errorConnectDB))) {
                writer.write("Không thể kết nối tới database: " + clean.DB_STAGING);
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không thể kết nối DB: " + clean.DB_STAGING);
        }
        if (conn2 == null) {
            File errorConnectDB = new File(errorFilePath + currentDate + "_source-crawl_connectDB-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(errorConnectDB))) {
                writer.write("Không thể kết nối tới database: " + clean.DB_CONTROL);
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không thể kết nối DB: " + clean.DB_CONTROL);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate localDate = LocalDate.parse(currentDate, formatter);
        String check_etl_log_status =
                "SELECT 1 process_code, status FROM etl_log " +
                        "WHERE process_code = 5 AND DATE(run_date) = ? " +
                        "ORDER BY log_id DESC LIMIT 1";
        PreparedStatement check_etl_log_status_stmt = conn2.prepareStatement(check_etl_log_status);
        check_etl_log_status_stmt.setDate(1, java.sql.Date.valueOf(localDate));
        ResultSet data = check_etl_log_status_stmt.executeQuery();

        // 4. Kiểm tra trạng thái process trong bảng etl_log trong DB control
        if (data.next()) {
            String status = data.getString("status");
            if (status.equals("PS")) {
                // Hiển thị lỗi "Ko thể chạy file do trạng thái hiện tại là: PS"
                throw new RuntimeException("Ko thể chạy file do trạng thái hiện tại là: " + status);
            }
        }

        // 5. Ghi log tiến trình đang thực hiện vào DB control
        // Truncate bảng stg_gold_price_clean
        // Lấy dữ liệu từ bảng stg_gold_price_detail
        String process_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
        PreparedStatement process_status_stmt1 = conn2.prepareStatement(process_sql1);
        process_status_stmt1.setInt(1, 5);
        process_status_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
        process_status_stmt1.setString(3, "PS");
        process_status_stmt1.setString(4, "Đang tiến hành tiến trình 5");
        process_status_stmt1.executeUpdate();

        String truncate_sql = "TRUNCATE TABLE stg_gold_price_clean";
        PreparedStatement truncate_stmt = conn1.prepareStatement(truncate_sql);
        truncate_stmt.executeUpdate();
        System.out.println("Bảng stg_gold_price_clean đã được truncate");

        String select_all = "SELECT DISTINCT d.brand, d.location, d.gold_type, d.buy_price, d.sell_price, d.unit, s.brand_url, d.`timestamp` " +
                "FROM stg_gold_price_detail AS d\n" +
                "JOIN stg_gold_price_source AS s\n" +
                "ON d.source_id = s.id";
        PreparedStatement select_stmt = conn1.prepareStatement(select_all);
        ResultSet rs = select_stmt.executeQuery();

        // Ghi log cho tiến trình trong DB control với lỗi ko lấy được dữ liệu từ bằng stg_gold_price_detail
        if (!rs.next()) {
            String fail_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement fail_status_stmt = conn2.prepareStatement(fail_sql);
            fail_status_stmt.setInt(1, 5);
            fail_status_stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            fail_status_stmt.setString(3, "FL");
            fail_status_stmt.setString(4, "Ko lấy được dữ liệu từ bằng stg_gold_price_detail");
            fail_status_stmt.executeUpdate();
            throw new RuntimeException("Ko lấy được dữ liệu từ bằng stg_gold_price_detail");
        }

        String insert_sql = "INSERT INTO stg_gold_price_clean " +
                "(record_id, brand, location, gold_type, buy_price, sell_price, unit, source, `timestamp`, load_date) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement insert_stmt = conn1.prepareStatement(insert_sql);
        while (rs.next()) {
            String id1 = clean.formatString(rs.getString("location"));
            String id2 = clean.formatString(rs.getString("brand"));
            String id3 = clean.formatString(rs.getString("gold_type"));

            String brand = rs.getString("brand");
            String location = rs.getString("location");
            String gold_type = rs.getString("gold_type");
            String unit = rs.getString("unit");
            String brand_url = rs.getString("brand_url");
            String timestamp = rs.getString("timestamp");
            String buyPriceStr = rs.getString("buy_price");
            String sell_priceStr = rs.getString("sell_price");

            if (gold_type == null || gold_type.trim().isEmpty() || brand == null || brand.trim().isEmpty() || location == null || location.trim().isEmpty()) {
                // Bỏ qua dòng giá trị này và tiếp tục tiến trình
                continue;
            }
            // Gán giá trị cho buy_price, sell_price là -1
            double buyPrice = (!buyPriceStr.equals("-")) ? Double.parseDouble(buyPriceStr) : -1;
            double sell_price = (!sell_priceStr.equals("-")) ? Double.parseDouble(sell_priceStr) : -1;

            insert_stmt.setString(1, id1 + "-" + id2 + "-" + id3);
            insert_stmt.setString(2, brand);
            insert_stmt.setString(3, location);
            insert_stmt.setString(4, gold_type);
            insert_stmt.setDouble(5, buyPrice);
            insert_stmt.setDouble(6, sell_price);
            insert_stmt.setString(7, unit);
            insert_stmt.setString(8, brand_url);
            insert_stmt.setString(9, timestamp);
            Timestamp loadDateTime = Timestamp.valueOf(LocalDateTime.now());
            insert_stmt.setTimestamp(10, loadDateTime);

            // 8. Thêm dữ liệu vào bảng stg_gold_price_clean
            //và
            //Ghi log cho tiến trình sau khi chạy xong trong DB control
            insert_stmt.executeUpdate();
        }

        // 8. Thêm dữ liệu vào bảng stg_gold_price_clean
        //và
        //Ghi log cho tiến trình sau khi chạy xong trong DB control
        String success_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
        PreparedStatement success_status_stmt = conn2.prepareStatement(success_sql);
        success_status_stmt.setInt(1, 5);
        success_status_stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
        success_status_stmt.setString(3, "SC");
        success_status_stmt.setString(4, "Hoàn thành tiến trình 5");
        success_status_stmt.executeUpdate();
        System.out.println("Insert dữ liệu hoàn tất");
    }
}
