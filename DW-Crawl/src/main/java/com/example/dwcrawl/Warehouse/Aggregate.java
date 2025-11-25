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

    // Lay gia tri bat buoc tu Properties, throw exception neu null hoac rong
    private static String getRequiredProperty(Properties props, String key, String name) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException("Giá trị config " + name + " không được null hoặc rỗng (key='" + key + "')");
        }
        return value.trim();
    }

    // 1. Load file config.xml
    // Doc file cau hinh XML, tao thu muc error neu chua co
    // Throw exception neu file khong ton tai
    public void loadConfig() {
        if (config_file_path == null || config_file_path.trim().isEmpty()) {
            config_file_path = "config.xml";
            System.out.println("Warning: config_file_path chưa truyền. Thử sử dụng 'config.xml' ở working dir.");
        }

        File configFile = new File(config_file_path);

        // Tao thu muc chua file error neu chua ton tai
        try {
            File errorDir = new File(errorFilePath);
            if (!errorDir.exists()) {
                errorDir.mkdirs();
            }
        } catch (Exception ex) {
            System.err.println("Không thể tạo thư mục errorFilePath: " + ex.getMessage());
        }

        // Kiem tra file config co ton tai khong
        if (!configFile.exists()) {
            File errorFile = new File(errorFilePath + currentDate + "_aggregate_config-error.txt");
            try (FileWriter writer = new FileWriter(errorFile)) {
                writer.write("Không tìm thấy file config.xml tại: " + config_file_path);
                writer.write(System.lineSeparator());
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không tìm thấy file config.xml tại: " + config_file_path);
        }

        System.out.println("Đã tìm thấy file config.xml tại: " + config_file_path);
    }

    // 2. Gán các giá trị cần thiết từ file config
    // Parse XML va lay thong tin ket noi database
    // Ghi loi vao file neu khong the load duoc gia tri
    public void assignConfigValues() {
        File configFile = new File(config_file_path);

        try (InputStream input = new FileInputStream(configFile)) {
            Properties props = new Properties();
            props.loadFromXML(input);

            // In ra tat ca cac gia tri config de debug
            props.forEach((key, value) -> System.out.println(key + " = " + value));

            // Lay cac gia tri bat buoc tu config
            DB_WAREHOUSE = getRequiredProperty(props, "db_warehouse", "DB_WAREHOUSE");
            DB_CONTROL = getRequiredProperty(props, "db_control", "DB_CONTROL");
            USER = getRequiredProperty(props, "db_user_root_name", "USER");
            PASSWORD = getRequiredProperty(props, "db_user_root_pass", "PASSWORD");

            System.out.println("Đã load config thành công:");
            System.out.println("DB_WAREHOUSE: " + DB_WAREHOUSE);
            System.out.println("DB_CONTROL: " + DB_CONTROL);
            System.out.println("USER: " + USER);

        } catch (Exception e) {
            // Ghi chi tiet loi vao file txt
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

    // 3. Kết nối tới db
    // Kết nối tới Warehouse database
    // Return connection neu thanh cong, null neu that bai
    public Connection connectToWarehouse() {
        try {
            Connection conn = DriverManager.getConnection(DB_WAREHOUSE, USER, PASSWORD);
            System.out.println("Connected to Warehouse DB successfully!");
            return conn;
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());

            // Ghi loi vao file
            File errorFile = new File(errorFilePath + currentDate + "_aggregate_warehouse-connection-error.txt");
            try (FileWriter writer = new FileWriter(errorFile)) {
                writer.write("Không thể kết nối DB Warehouse: " + DB_WAREHOUSE);
                writer.write(System.lineSeparator());
                writer.write("Error: " + e.getMessage());
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            return null;
        }
    }

    // Kết nối tới Control database
    // Return connection neu thanh cong, null neu that bai
    public Connection connectToControl() {
        try {
            Connection conn = DriverManager.getConnection(DB_CONTROL, USER, PASSWORD);
            System.out.println("Connected to Control DB successfully!");
            return conn;
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());

            // Ghi loi vao file
            File errorFile = new File(errorFilePath + currentDate + "_aggregate_control-connection-error.txt");
            try (FileWriter writer = new FileWriter(errorFile)) {
                writer.write("Không thể kết nối DB Control: " + DB_CONTROL);
                writer.write(System.lineSeparator());
                writer.write("Error: " + e.getMessage());
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            return null;
        }
    }

    // 4. Kiểm tra trạng thái process 6 trong bảng etl_log trong DB control
    // Process 6 (Load Warehouse) phai chay thanh cong truoc khi chay process 7
    // Throw exception neu khong tim thay process 6 thanh cong
    public void checkProcess6Status(Connection connControl) throws SQLException {
        String check_process6_status =
                "SELECT run_date, status FROM etl_log " +
                        "WHERE process_code = 6 AND status = 'SC' " +
                        "ORDER BY run_date DESC LIMIT 1";

        try (PreparedStatement stmt = connControl.prepareStatement(check_process6_status);
             ResultSet rs = stmt.executeQuery()) {

            if (!rs.next()) {
                String errorMsg = "Không tìm thấy lần chạy thành công của Process 6 (Load Warehouse).";

                // Ghi loi vao file txt
                File errorFile = new File(errorFilePath + currentDate + "_aggregate_process6-error.txt");
                try (FileWriter writer = new FileWriter(errorFile)) {
                    writer.write(errorMsg);
                    writer.write(System.lineSeparator());
                    writer.write("Yêu cầu: Process 6 phải chạy thành công (status='SC') trước khi chạy Process 7.");
                } catch (IOException ioEx) {
                    ioEx.printStackTrace();
                }
                throw new RuntimeException(errorMsg);
            }

            Timestamp latestProcess6Run = rs.getTimestamp("run_date");
            System.out.println("Process 6 gần nhất chạy thành công vào: " + latestProcess6Run);
        }
    }

    // Ghi log bat dau process vao bang etl_log
    // Status = 'PS' (Processing)
    public void logProcessStart(Connection connControl) throws SQLException {
        String process_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
        try (PreparedStatement stmt = connControl.prepareStatement(process_sql)) {
            stmt.setInt(1, 7);
            stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(3, "PS");
            stmt.setString(4, "Đang tiến hành tiến trình 7 - Aggregate");
            stmt.executeUpdate();
            System.out.println("Đã ghi log bắt đầu process 7");
        }
    }

    // Tính toán avg_buy_price, avg_sell_price, record_count vào bảng aggregate_gold dựa vào bảng gold_price
    // Sử dụng TEMP TABLE để backup dữ liệu trước khi truncate
    // Nếu có lỗi sẽ tự động restore dữ liệu từ bảng tạm
    public void performAggregate(Connection connWarehouse) throws SQLException {
        System.out.println("=== Sử dụng TEMP TABLE để backup và aggregate ===");

        // Bat dau transaction
        connWarehouse.setAutoCommit(false);

        try {
            // Bước 1: Tạo bảng tạm và backup toàn bộ dữ liệu hiện tại
            System.out.println("Bước 1: Tạo bảng tạm và backup dữ liệu...");
            String createTempSQL =
                    "CREATE TEMPORARY TABLE aggregate_gold_backup " +
                            "SELECT * FROM aggregate_gold";

            try (PreparedStatement stmt = connWarehouse.prepareStatement(createTempSQL)) {
                stmt.executeUpdate();
                System.out.println("Đã backup dữ liệu vào bảng tạm aggregate_gold_backup");
            }

            // Bước 2: Truncate bảng chính để xóa toàn bộ dữ liệu cũ
            System.out.println("Bước 2: Truncate bảng aggregate_gold...");
            String truncateSQL = "TRUNCATE TABLE aggregate_gold";

            try (PreparedStatement stmt = connWarehouse.prepareStatement(truncateSQL)) {
                stmt.executeUpdate();
                System.out.println("Đã truncate bảng aggregate_gold");
            }

            // Bước 3: Tính toán và insert dữ liệu mới
            // Tính trung bình giá mua, giá bán và đếm số lượng record
            // Group theo brand_sk, location_sk, date_id
            System.out.println("Bước 3: Tính toán và insert dữ liệu mới...");
            String aggregateSQL =
                    "INSERT INTO aggregate_gold " +
                            "   (brand_sk, location_sk, date_id, avg_buy_price, avg_sell_price, record_count, load_time) " +
                            "SELECT " +
                            "   brand_sk, " +
                            "   location_sk, " +
                            "   date_id, " +
                            "   AVG(buy_price) AS avg_buy_price, " +
                            "   AVG(sell_price) AS avg_sell_price, " +
                            "   COUNT(*) AS record_count, " +
                            "   NOW() AS load_time " +
                            "FROM gold_price " +
                            "WHERE is_deleted = 0 " +
                            "GROUP BY brand_sk, location_sk, date_id";

            try (PreparedStatement stmt = connWarehouse.prepareStatement(aggregateSQL)) {
                int rowsAffected = stmt.executeUpdate();
                System.out.println("Đã insert " + rowsAffected + " records vào aggregate_gold");
            }

            // Commit transaction nếu thành công
            connWarehouse.commit();
            System.out.println("Hoàn thành aggregate thành công (TEMP TABLE + TRUNCATE)");

        } catch (SQLException e) {
            System.err.println("Lỗi khi thực hiện aggregate: " + e.getMessage());

            // Rollback transaction
            connWarehouse.rollback();
            System.out.println("Đang rollback và restore dữ liệu từ bảng tạm...");

            // Restore dữ liệu từ bảng tạm
            String restoreSQL = "INSERT INTO aggregate_gold SELECT * FROM aggregate_gold_backup";
            try (PreparedStatement stmt = connWarehouse.prepareStatement(restoreSQL)) {
                int restored = stmt.executeUpdate();
                System.out.println("Đã restore " + restored + " bản ghi từ bảng tạm");
                connWarehouse.commit();
            }

            throw e;

        } finally {
            connWarehouse.setAutoCommit(true);
        }
    }

    // 6. Ghi log tiến trình sau khi chạy xong trong DB control
    // Status = 'SC' (Success)
    public void logProcessSuccess(Connection connControl) throws SQLException {
        String success_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
        try (PreparedStatement stmt = connControl.prepareStatement(success_sql)) {
            stmt.setInt(1, 7);
            stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(3, "SC");
            stmt.setString(4, "Hoàn thành tiến trình 7 - Aggregate");
            stmt.executeUpdate();
            System.out.println("Đã ghi log hoàn thành process 7");
        }
    }

    // Ghi chi tiết lỗi vào bảng error_log trong DB control
    public void logErrorToDatabase(Connection connControl, String errorMessage) {
        String detail_error_sql = "INSERT INTO error_log (process_code, error_time, error_message, error_file) VALUES (?,?,?,?);";
        try (PreparedStatement stmt = connControl.prepareStatement(detail_error_sql)) {
            stmt.setInt(1, 7);
            stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(3, "Lỗi tiến trình 7 - Aggregate: " + errorMessage);
            stmt.setString(4, "N/A");
            stmt.executeUpdate();
            System.out.println("Đã ghi lỗi vào error_log");
        } catch (SQLException e) {
            System.err.println("Không thể ghi lỗi vào DB control: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws SQLException {
        Aggregate aggregate = new Aggregate();

        // Parse tham so tu command line
        for (String arg : args) {
            if (arg.startsWith("config_file_path=")) {
                aggregate.config_file_path = arg.substring("config_file_path=".length()).trim();
                System.out.println("Using config file: " + aggregate.config_file_path);
            } else if (arg.startsWith("date=")) {
                currentDate = arg.substring("date=".length()).trim();
                System.out.println("Using specified date: " + currentDate);
            }
        }

        Connection connWarehouse = null;
        Connection connControl = null;

        try {
            // 1. Load file config.xml
            aggregate.loadConfig();

            // 2. Gán các giá trị cần thiết từ file config
            aggregate.assignConfigValues();

            // 3. Kết nối tới database
            connWarehouse = aggregate.connectToWarehouse();
            connControl = aggregate.connectToControl();

            // Kiểm tra kết nối
            if (connWarehouse == null) {
                throw new RuntimeException("Không thể kết nối DB Warehouse: " + aggregate.DB_WAREHOUSE);
            }
            if (connControl == null) {
                throw new RuntimeException("Không thể kết nối DB Control: " + aggregate.DB_CONTROL);
            }

            // 4. Kiểm tra trạng thái process 6 trong bảng etl_log trong DB control
            aggregate.checkProcess6Status(connControl);

            // 5. Ghi log bắt đầu tiến trình và tính toán avg_buy_price, avg_sell_price, record_count
            // vào bảng aggregate_gold dựa vào bảng gold_price
            aggregate.logProcessStart(connControl);
            aggregate.performAggregate(connWarehouse);

            // 6. Ghi log tiến trình sau khi chạy xong trong DB control
            aggregate.logProcessSuccess(connControl);

            System.out.println("Tiến trình Aggregate hoàn thành thành công!");

        } catch (Exception e) {
            // Xử lý log và ghi lỗi
            System.err.println("Lỗi trong quá trình aggregate: " + e.getMessage());
            e.printStackTrace();

            // Ghi lỗi vào database
            if (connControl != null) {
                aggregate.logErrorToDatabase(connControl, e.getMessage());
            }

            // Ghi lỗi vào file txt
            File errorFile = new File(errorFilePath + currentDate + "_aggregate_general-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(errorFile))) {
                writer.write("Lỗi tiến trình 7 - Aggregate: " + e.getMessage());
                writer.newLine();
                writer.write("Thời gian: " + LocalDateTime.now());
                writer.newLine();
                writer.write("Stack trace:");
                writer.newLine();
                e.printStackTrace(new PrintWriter(writer));
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }

            throw new RuntimeException("Aggregate failed: " + e.getMessage(), e);

        } finally {
            // Đóng tất cả kết nối
            if (connWarehouse != null) try { connWarehouse.close(); } catch (SQLException ignored) {}
            if (connControl != null) try { connControl.close(); } catch (SQLException ignored) {}
        }
    }
}