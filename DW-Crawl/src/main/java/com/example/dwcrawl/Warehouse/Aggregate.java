package com.example.dwcrawl.Warehouse;

import java.io.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class Aggregate {
    private String DB_WAREHOUSE;
    private String DB_CONTROL;
    private String USER;
    private String PASSWORD;
    private static String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
    private static final String errorFilePath = "/DW/control/config_error/";
    private String config_file_path;

    private static String getRequiredProperty(Properties props, String key, String name) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException("Giá trị config " + name + " không được null hoặc rỗng (key='" + key + "')");
        }
        return value.trim();
    }

    public Connection connectToWarehouse() {
        try {
            Connection conn = DriverManager.getConnection(DB_WAREHOUSE, USER, PASSWORD);
            System.out.println("Connected to Warehouse DB successfully!");
            return conn;
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            return null;
        }
    }

    public Connection connectToControl() {
        try {
            Connection conn = DriverManager.getConnection(DB_CONTROL, USER, PASSWORD);
            System.out.println("Connected to Control DB successfully!");
            return conn;
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            return null;
        }
    }

    public void loadConfig() {
        if (config_file_path == null || config_file_path.trim().isEmpty()) {
            config_file_path = "config.xml";
            System.out.println("Warning: config_file_path chưa truyền. Thử sử dụng 'config.xml' ở working dir.");
        }

        File configFile = new File(config_file_path);

        try {
            File errorDir = new File(errorFilePath);
            if (!errorDir.exists()) {
                errorDir.mkdirs();
            }
        } catch (Exception ex) {
            System.err.println("Không thể tạo thư mục errorFilePath: " + ex.getMessage());
        }

        if (!configFile.exists()) {
            File errorFile = new File(errorFilePath + currentDate + "_aggregate_config-error.txt");
            try (FileWriter writer = new FileWriter(errorFile)) {
                writer.write("Ko tìm thấy file config.xml tại: " + config_file_path);
                writer.write(System.lineSeparator());
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không tìm thấy file config.xml tại: " + config_file_path);
        }

        try (InputStream input = new FileInputStream(configFile)) {
            Properties props = new Properties();
            props.loadFromXML(input);

            props.forEach((key, value) -> System.out.println(key + " = " + value));

            DB_WAREHOUSE = getRequiredProperty(props, "db_warehouse", "DB_WAREHOUSE");
            DB_CONTROL = getRequiredProperty(props, "db_control", "DB_CONTROL");
            USER = getRequiredProperty(props, "db_user_root_name", "USER");
            PASSWORD = getRequiredProperty(props, "db_user_root_pass", "PASSWORD");

            System.out.println("Đã load config thành công:");
            System.out.println("DB_WAREHOUSE: " + DB_WAREHOUSE);
            System.out.println("DB_CONTROL: " + DB_CONTROL);
            System.out.println("USER: " + USER);
        } catch (Exception e) {
            File error_loadConfig_value = new File(errorFilePath + currentDate + "_aggregate_loadConfigValue-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(error_loadConfig_value))) {
                writer.write("Không thể load giá trị trong config.xml: " + e.getMessage());
                writer.newLine();
                writer.write("Stack trace:");
                writer.newLine();
                e.printStackTrace(new PrintWriter(writer));
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Lỗi load config.xml: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) throws SQLException {
        Aggregate aggregate = new Aggregate();

        // 1. Load file config.xml
        for (String arg : args) {
            if (arg.startsWith("config_file_path=")) {
                aggregate.config_file_path = arg.substring("config_file_path=".length()).trim();
                System.out.println("Using config file: " + aggregate.config_file_path);
            } else if (arg.startsWith("date=")) {
                currentDate = arg.substring("date=".length()).trim();
                System.out.println("Using specified date: " + currentDate);
            }
        }
        aggregate.loadConfig();

        // 2. Kết nối tới database
        Connection connWarehouse = aggregate.connectToWarehouse();
        Connection connControl = aggregate.connectToControl();

        // 3. Xuất file .txt báo lỗi không thể kết nối đến DB
        if (connWarehouse == null) {
            throw new RuntimeException("Không thể kết nối DB Warehouse: " + aggregate.DB_WAREHOUSE);
        }
        if (connControl == null) {
            throw new RuntimeException("Không thể kết nối DB Control: " + aggregate.DB_CONTROL);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
        LocalDate localDate = LocalDate.parse(currentDate, formatter);

        // 4. Kiểm tra trạng thái process 6 (Load Warehouse) trong bảng etl_log trong DB control
        String check_process6_status =
                "SELECT run_date, status FROM etl_log " +
                        "WHERE process_code = 6 AND status = 'SC' " +
                        "ORDER BY run_date DESC LIMIT 1";
        PreparedStatement check_process6_stmt = connControl.prepareStatement(check_process6_status);
        ResultSet rs = check_process6_stmt.executeQuery();

        if (!rs.next()) {
            throw new RuntimeException("Không tìm thấy bất kỳ lần chạy thành công nào của Process 6 (Load Warehouse).");
        }
        Timestamp latestProcess6Run = rs.getTimestamp("run_date");
        System.out.println("Process 6 gần nhất chạy thành công vào: " + latestProcess6Run);

        // Kiểm tra trạng thái process 7 hiện tại
        String check_process7_status =
                "SELECT status FROM etl_log " +
                        "WHERE process_code = 7 AND DATE(run_date) = ? " +
                        "ORDER BY log_id DESC LIMIT 1";
        PreparedStatement check_process7_stmt = connControl.prepareStatement(check_process7_status);
        check_process7_stmt.setDate(1, java.sql.Date.valueOf(localDate));
        ResultSet rs7 = check_process7_stmt.executeQuery();

        try {
            // 5. Ghi log tiến trình đang thực hiện vào DB control
            String process_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement process_stmt = connControl.prepareStatement(process_sql);
            process_stmt.setInt(1, 7);
            process_stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            process_stmt.setString(3, "PS");
            process_stmt.setString(4, "Đang tiến hành tiến trình 7 - Aggregate");
            process_stmt.executeUpdate();

            // 6. Truncate bảng aggregate_gold
            System.out.println("Bắt đầu truncate bảng aggregate_gold...");
            connWarehouse.prepareStatement("SET FOREIGN_KEY_CHECKS = 0").execute(); // Tạm thời bỏ FK
            PreparedStatement truncAgg = connWarehouse.prepareStatement("TRUNCATE TABLE aggregate_gold");
            truncAgg.executeUpdate();
            System.out.println("Đã truncate bảng aggregate_gold");

            // 7. Tính toán avg_buy_price, avg_sell_price, record_count vào bảng aggregate_gold dựa vào bảng gold_price
            String aggregateSQL =
                    "INSERT INTO aggregate_gold (brand_sk, location_sk, avg_buy_price, avg_sell_price, record_count, load_time) " +
                            "SELECT " +
                            "    brand_sk, " +
                            "    location_sk, " +
                            "    AVG(buy_price) as avg_buy_price, " +
                            "    AVG(sell_price) as avg_sell_price, " +
                            "    COUNT(*) as record_count, " +
                            "    NOW() as load_time " +
                            "FROM gold_price " +
                            "WHERE is_deleted = 0 " +
                            "GROUP BY brand_sk, location_sk";

            // 8. Lưu dữ liệu aggregate mới
            PreparedStatement aggStmt = connWarehouse.prepareStatement(aggregateSQL);
            int rowsInserted = aggStmt.executeUpdate();
            System.out.println("Đã insert " + rowsInserted + " records vào aggregate_gold");

            connWarehouse.prepareStatement("SET FOREIGN_KEY_CHECKS = 1").execute(); // Bật lại FK

            // 9. Ghi log tiến trình sau khi chạy xong trong DB control
            String success_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement success_stmt = connControl.prepareStatement(success_sql);
            success_stmt.setInt(1, 7);
            success_stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            success_stmt.setString(3, "SC");
            success_stmt.setString(4, "Hoàn thành tiến trình 7 - Aggregate với " + rowsInserted + " records");
            success_stmt.executeUpdate();

            System.out.println("Tiến trình Aggregate hoàn thành thành công!");

        } catch (Exception e) {
            // 10. Ghi log tiến trình lỗi
            System.err.println("Lỗi trong quá trình aggregate: " + e.getMessage());
            e.printStackTrace();

            try {
                // Ghi chi tiết lỗi vào control.error_log
                String detail_error_sql = "INSERT INTO error_log (process_code, error_time, error_message, error_file) VALUES (?,?,?,?);";
                PreparedStatement s3 = connControl.prepareStatement(detail_error_sql);
                s3.setInt(1, 7);
                s3.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                s3.setString(3, "Lỗi tiến trình 7 - Aggregate: " + e.getMessage());
                s3.setString(4, "N/A");
                s3.executeUpdate();

                e.printStackTrace();
            } catch (Exception inner) {
                System.err.println("Không thể ghi lỗi vào DB control: " + inner.getMessage());
                inner.printStackTrace();
            }

            throw new RuntimeException("Aggregate failed: " + e.getMessage(), e);
        } finally {
            if (connWarehouse != null) try { connWarehouse.close(); } catch (SQLException ignored) {}
            if (connControl != null) try { connControl.close(); } catch (SQLException ignored) {}
        }
    }
}