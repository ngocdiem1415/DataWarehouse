package com.example.dwcrawl.Staging;

import com.opencsv.CSVReader;

import java.io.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class LoadDetail {
    private String DB_STAGING;
    private String DB_CONTROL;
    private String USER;
    private String PASSWORD;
    private String outputPath;
    private static String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
//    private static final String errorFilePath = "D:/DataWarehouse/DW-Crawl/";
    private static final String errorFilePath = "/DW/control/config_error/";
    private String config_file_path;

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

        // Xuất file .txt báo lỗi ko load được file config.xml
        if (!configFile.exists()) {
            File errorFile = new File(errorFilePath + currentDate + "_load-detail" + "config-error.txt");
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
            outputPath = getRequiredProperty(props, "output.path", "OUTPUT_PATH");
            DB_STAGING = getRequiredProperty(props, "db_staging", "DB_STAGING");
            DB_CONTROL = getRequiredProperty(props, "db_control", "DB_CONTROL");
            USER = getRequiredProperty(props, "db_user_root_name", "USER");
            PASSWORD = getRequiredProperty(props, "db_user_root_pass", "PASSWORD");
        }
        // Xuất file .txt báo lỗi ko load được giá trị trong file config.xml
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
        LoadDetail load = new LoadDetail();

        // 1. Load file config.xml
        for (String arg : args) { // Input: config_file_path
            if (arg.startsWith("config_file_path=")) {
                load.config_file_path = arg.substring("config_file_path=".length()).trim();
                System.out.println("Using config file: " + load.config_file_path);
            } else if (arg.startsWith("date=")) {
                currentDate = arg.substring("date=".length()).trim();
                System.out.println("Using specified date: " + currentDate);
            }
        }
        load.loadConfig();

        // 3. Kết nối tới database
        Connection conn1 = load.connectToStaging();
        Connection conn2 = load.connectToControl();

        // Xuất file .txt báo lỗi ko thể kết nối đến DB
        if (conn1 == null) {
            File errorConnectDB = new File(errorFilePath + currentDate + "_source-crawl_connectDB-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(errorConnectDB))) {
                writer.write("Không thể kết nối tới database: " + load.DB_STAGING);
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không thể kết nối DB: " + load.DB_STAGING);
        }
        if (conn2 == null) {
            File errorConnectDB = new File(errorFilePath + currentDate + "_source-crawl_connectDB-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(errorConnectDB))) {
                writer.write("Không thể kết nối tới database: " + load.DB_CONTROL);
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không thể kết nối DB: " + load.DB_CONTROL);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
        LocalDate localDate = LocalDate.parse(currentDate, formatter);
        String check_etl_log_status =
                "SELECT 1 process_code, status FROM etl_log " +
                        "WHERE process_code = 4 AND DATE(run_date) = ? " +
                        "ORDER BY log_id DESC LIMIT 1";
        PreparedStatement check_etl_log_status_stmt = conn2.prepareStatement(check_etl_log_status);
        check_etl_log_status_stmt.setDate(1, java.sql.Date.valueOf(localDate));
        ResultSet rs = check_etl_log_status_stmt.executeQuery();

        // 4. Kiểm tra trạng thái process trong bảng etl_log trong DB control
        if (rs.next()) {
            String status = rs.getString("status");
            if (status.equals("PS")) {
                // Hiển thị lỗi
                throw new RuntimeException("Ko thể chạy file do trạng thái hiện tại là: " + status);
            }
        }

        String csvFile = load.outputPath + "detail_crawl_" + currentDate + ".csv";

        // Ghi log vào DB control lỗi file giavang_ddmmYYYY.csv ko tồn tại
        File input_file = new File(csvFile);
        if (!input_file.exists()) {
            String input_file_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement input_file_stmt1 = conn2.prepareStatement(input_file_sql1);
            input_file_stmt1.setInt(1, 4);
            input_file_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            input_file_stmt1.setString(3, "FL");
            input_file_stmt1.setString(4, "Tiến trình 4 ko thể thực thi");
            input_file_stmt1.executeUpdate();

            String input_file_sql2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
            PreparedStatement input_file_stmt2 = conn2.prepareStatement(input_file_sql2);
            input_file_stmt2.setInt(1, 4);
            input_file_stmt2.setString(2, csvFile);
            input_file_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            input_file_stmt2.setString(4, "FL");
            input_file_stmt2.executeUpdate();

            String input_file_sql3 = "INSERT INTO error_log (process_code, error_time, error_message) VALUES (?,?,?);";
            PreparedStatement input_file_stmt3 = conn2.prepareStatement(input_file_sql3);
            input_file_stmt3.setInt(1, 4);
            input_file_stmt3.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            input_file_stmt3.setString(3, "File csv ko tồn tại: " + csvFile);
            input_file_stmt3.executeUpdate();
        }

        // 5. Ghi log tiến trình đang thực hiện vào DB control
        String process_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
        PreparedStatement process_status_stmt1 = conn2.prepareStatement(process_sql1);
        process_status_stmt1.setInt(1, 4);
        process_status_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
        process_status_stmt1.setString(3, "PS");
        process_status_stmt1.setString(4, "Đang tiến hành tiến trình 4");
        process_status_stmt1.executeUpdate();
        String process_sql2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
        PreparedStatement process_status_stmt2 = conn2.prepareStatement(process_sql2);
        process_status_stmt2.setInt(1, 4);
        process_status_stmt2.setString(2, csvFile);
        process_status_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
        process_status_stmt2.setString(4, "RD");
        process_status_stmt2.executeUpdate();

        // 6. Đọc dữ liệu từ file detail_crawl_ddmmYYYY.csv
        try (CSVReader reader = new CSVReader(new FileReader(csvFile))) {

            // 7. Ghi log cho tiến trình với trạng thái "RN" vào bảng file_config trong DB control
            String running_status_sql = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
            PreparedStatement running_stmt = conn2.prepareStatement(running_status_sql);
            running_stmt.setInt(1, 4);
            running_stmt.setString(2, csvFile);
            running_stmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            running_stmt.setString(4, "RN");
            running_stmt.executeUpdate();

            String insertSQL = "INSERT INTO stg_gold_price_detail (source_id, brand, location, gold_type, buy_price, sell_price, unit, timestamp) VALUES( ?, ?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement stmt = conn1.prepareStatement(insertSQL);
            String[] nextLine;
            reader.readNext(); // Bỏ header
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            // 8. Đọc dữ liệu từ detail_crawl_ddmmYYYY.csv và lưu vào bảng stg_gold_price_detail DB staging
            while ((nextLine = reader.readNext()) != null) {
                String sourceId = nextLine[0].trim();
                String brand = nextLine[2].trim();
                String location = nextLine[3].trim();
                String goldType = nextLine[4].trim();
                String buyPrice = nextLine[5].trim();
                String sellPrice = nextLine[6].trim();
                String unit = nextLine[7].trim();
                String timestamp = nextLine[8].trim();

                stmt.setString(1, sourceId);
                stmt.setString(2, brand);
                stmt.setString(3, location);
                stmt.setString(4, goldType);
                stmt.setString(5, buyPrice);
                stmt.setString(6, sellPrice);
                stmt.setString(7, unit);
                stmt.setString(8, timestamp);

                stmt.addBatch();
            }

            int[] result = stmt.executeBatch();
            System.out.println("Inserted " + result.length + " rows into bảng");

            // 9. Ghi log cho tiến trình sau khi chạy xong trong DB control
            String success_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement success_status_stmt1 = conn2.prepareStatement(success_sql1);
            success_status_stmt1.setInt(1, 4);
            success_status_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            success_status_stmt1.setString(3, "SC");
            success_status_stmt1.setString(4, "Hoàn thành tiến trình 4");
            success_status_stmt1.executeUpdate();
            String success_sql2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
            PreparedStatement success_status_stmt2 = conn2.prepareStatement(success_sql2);
            success_status_stmt2.setInt(1, 4);
            success_status_stmt2.setString(2, csvFile);
            success_status_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            success_status_stmt2.setString(4, "SC");
            success_status_stmt2.executeUpdate();
        }
        // Ghi log vào DB control đọc dữ liệu từ file giavang_ddmmYYYY.csv ko thành công
        catch (Exception e) {
            String input_file_error1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement input_file_error_stmt1 = conn2.prepareStatement(input_file_error1);
            input_file_error_stmt1.setInt(1, 4);
            input_file_error_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            input_file_error_stmt1.setString(3, "FL");
            input_file_error_stmt1.setString(4, "Đọc dữ liệu từ file " + csvFile + " ko thành công");
            input_file_error_stmt1.executeUpdate();

            String input_file_error2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
            PreparedStatement input_file_error_stmt2 = conn2.prepareStatement(input_file_error2);
            input_file_error_stmt2.setInt(1, 4);
            input_file_error_stmt2.setString(2, csvFile);
            input_file_error_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            input_file_error_stmt2.setString(4, "FL");
            input_file_error_stmt2.executeUpdate();

            String detail_error_sql = "INSERT INTO error_log (process_code, error_time, error_message, error_file) VALUES (?,?,?,?);";
            PreparedStatement s3 = conn2.prepareStatement(detail_error_sql);
            s3.setInt(1, 4);
            s3.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            s3.setString(3, "Đọc dữ liệu từ file " + csvFile + " ko thành công");
            s3.setString(4, csvFile);
            s3.executeUpdate();
            e.printStackTrace();
        }
    }
}