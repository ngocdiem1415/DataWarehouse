package com.example.dwcrawl.Warehouse;

import com.opencsv.CSVReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class LoadDatamart {
    private String DB_CONTROL_URL;
    private String DB_WAREHOUSE_URL;
    private String DB_MART_URL;

    private String USER;
    private String PASSWORD;
    private String outputPath;
    private String configPath;
    private String date;
    private static final String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
    private String pathFileError;

    private Connection controlConn = null; // Kết nối đến DB Control
    private Connection dataConn = null; // Kết nối đến DB cần xử lí

    private static final int PROCESS_CODE = 8;

    // 1. Load file config
    public void loadConfig(String config, String date) {
        this.date = date != null ? date : currentDate;
        this.configPath = config;
        // Tạo file config từ đường dẫn được truyền vào
        File configFile = new File(config);

        // Nếu không tìm thấy thì quăng lỗi
        if (!configFile.exists()) {
            // Gọi hàm ghi log trong error_log
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Không tìm thấy file config.xml", "N/A"));
            // Gọi hàm ghi log trong etl_log
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Không tìm thấy file config.xml", "FL"));
            throw new RuntimeException("Không tìm thấy file config.xml tại: " + configFile.getAbsolutePath());
        }
        // Đọc file
        try (InputStream input = new FileInputStream(configFile)) {
            Properties props = new Properties();
            props.loadFromXML(input);
            DB_CONTROL_URL = props.getProperty("db_control");
            DB_WAREHOUSE_URL = props.getProperty("db_warehouse");
            DB_MART_URL = props.getProperty("db_mart");
            USER = props.getProperty("db_user_root_name");
            PASSWORD = props.getProperty("db_user_root_pass");
            outputPath = props.getProperty("output.mart");
            System.out.println("Đã load xong file config");
        } catch (Exception e) {
            // Gọi hàm ghi log trong error_log
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Không tìm thấy file config.xml", "N/A"));
            // Gọi hàm ghi log trong etl_log
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Lỗi load config.xml", "FL"));
            throw new RuntimeException("Lỗi load config.xml: " + e.getMessage(), e);
        }
    }

    // 2.Kết nối db control
    public void connectDBControl() throws SQLException {
        try {
            // Gán kết nối vào biến thành viên conn
            this.controlConn = DriverManager.getConnection(DB_CONTROL_URL, USER, PASSWORD);
            System.out.println("Kết nối DB Control thành công.");
        } catch (SQLException e) {
            // Gọi hàm ghi log trong error_log
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Lỗi kết nối đến DB Control", "N/A"));
            // Gọi hàm ghi log trong etl_log
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Lỗi kết nối đến DB Control", "FL"));
            System.err.println("Lỗi kết nối đến DB Control: " + e.getMessage());
            throw new RuntimeException("Lỗi kết nối db: " + e.getMessage(), e);
        }
    }

    // 2.Kết nối db leien quan đến dữ liệu
    public void connectDBDT(String url, String dbName) throws SQLException {
        if (this.dataConn != null) {
            try {
                this.dataConn.close();
                this.dataConn = null;
            } catch (SQLException e) {
                insertLogToErrorLog("Lỗi khi đóng kết nối cũ trước khi mở " + dbName, "N/A");
            }
        }
        try {
            // Gán kết nối vào biến thành viên conn
            this.dataConn = DriverManager.getConnection(url, USER, PASSWORD);
            System.out.println("Kết nối DB" + dbName + " thành công.");

        } catch (SQLException e) {
            // Gọi hàm ghi log trong error_log
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Lỗi kết nối đến DB " + dbName, pathFileError));
            // Gọi hàm ghi log trong etl_log
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Lỗi kết nối đến DB " + dbName, "FL"));
            System.err.println("Lỗi kết nối đến DB" + dbName + e.getMessage());
            throw new RuntimeException("Lỗi kết nối db: " + e.getMessage(), e);
        }
    }

    //Kiểm tra record trong etl_log
    public boolean checkRecordETLLog() {
        //Ghi log process đang bắt dầu chạy
        System.out.println("Ghi log etl_log thành công " + insertLogToETLLog("Process dump aggregate đang bắt đầu chạy ", "PS"));
        String sql = "SELECT 1 FROM etl_log " +
                "WHERE process_code = ? AND DATE_FORMAT(run_date, '%d%m%Y') = ? AND status = 'SC'";
        // PROCESS_ID stt hiện tại của script trước khi chạy cần kiểm tra xem script
        int processCode = PROCESS_CODE - 1;

        try (PreparedStatement pstmt = controlConn.prepareStatement(sql)) {
            //Kiểm tra record có process_code = 7, date = input[date], status = SC
            // trước có chạy thành công chưa
            pstmt.setInt(1, processCode);
            pstmt.setString(2, this.date);

            try (ResultSet rs = pstmt.executeQuery()) {
                // Nếu có ít nhất một dòng, tức là đã có record thành công
                if (rs.next()) {
                    System.out.println("Đã có record thành công (process_code=" + processCode + ", date=" + this.date + ") trong etl_log.");
                    return true;
                }
            }
            System.out.println("Record trước đó đã bị lỗi");
            // Gọi hàm ghi log trong error_log
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Record trước đó đã bị lỗi", pathFileError));
            // Gọi hàm ghi log trong etl_log
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Record trước đó đã bị lỗi", "FL"));
            return false;
        } catch (SQLException e) {
            // Gọi hàm ghi log trong error_log
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Lỗi khi kiểm tra etl_log", pathFileError));
            // Gọi hàm ghi log trong etl_log
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Lỗi khi kiểm tra etl_log", "FL"));
            throw new RuntimeException("Lỗi kiểm tra etl_log: ", e);

        }
    }

    public void exportTablesToCSV(List<String> tableNames) {
        if (!checkRecordETLLog()) {
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Process trước đó chưa thành công. Không thực hiện export.", "N/A"));
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Process trước đó chưa thành công. Không thực hiện export.", "FL"));
            System.out.println("Dừng Export: Process trước đó chưa thành công. Không thực hiện export.");
            return;
        }

        boolean overallSuccess = true;
        for (String tableName : tableNames) {
            try {
                exportSingleTableToCSV(tableName);

            } catch (RuntimeException e) {
                // Lỗi đã được ghi log trong hàm exportSingleTableToCSV,
                overallSuccess = false;
                System.err.println("Dừng Export do lỗi nghiêm trọng khi xử lý bảng: " + tableName);
                break;
            }
        }
    }

    //Dump ra aggregate csv
    public void exportSingleTableToCSV(String tableName) {
        String csvFileName = "dump_" + tableName + "_" + this.date + ".csv";
        String outputFile = outputPath + csvFileName;
        System.out.println("Bắt đầu export bảng: " + tableName);

        String query = "SELECT * FROM " + tableName;

        // tạo kết nối với db
        try (PreparedStatement pstmt = dataConn.prepareStatement(query);
             // rs chứ dữ liệu được trả về
             ResultSet rs = pstmt.executeQuery();
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(outputFile, false), StandardCharsets.UTF_8)) // Dùng false để GHI ĐÈ, không phải nối thêm (append)
        ) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            int count = 1;

            // Ghi header CSV
            for (int i = 1; i <= columnCount; i++) {
                writer.write(meta.getColumnName(i));
                if (i < columnCount) writer.write(",");
            }
            writer.write("\n");

            // Ghi từng dòng
            while (rs.next()) {
                // Chỉ in ra log mỗi 1000 dòng để tránh quá tải console
                if (count % 1000 == 1) {
                    System.out.println(count + "/ Export row for " + tableName + "...");
                }
                for (int i = 1; i <= columnCount; i++) {
                    String value = rs.getString(i);
                    writer.write(escapeCsv(value));
                    if (i < columnCount) writer.write(",");
                }
                writer.write("\n");
                count++;
            }

            // Cần đảm bảo file được ghi vào file_config có tên bảng chính xác
            System.out.println("Ghi log file_config thành công " + insertLogToFileConfig(tableName, outputFile, "RD"));
            System.out.println("Hoàn tất export CSV tại: " + outputFile);
            // *** ĐÃ XÓA closeConnectionDB() ***

        } catch (Exception e) {
            // Ghi log lỗi và ném RuntimeException để dừng quá trình Export
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Lỗi khi export CSV cho bảng " + tableName, pathFileError));
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Lỗi khi export CSV cho bảng " + tableName, "FL"));
            throw new RuntimeException("Lỗi khi export CSV cho " + tableName + ": ", e);
        }
    }

    // Format chuỗi string về định dạng chuẩn của csv
    private static String escapeCsv(String value) {
        if (value == null) return "";
        value = value.replace("\"", "\"\""); // escape dấu "
        return "\"" + value + "\""; // bao quanh bởi "
    }

    // Định nghĩa thứ tự Load (Tên bảng, Tên file CSV, Khóa chính (để kiểm tra sau Insert))
    public List<String[]> loadOrder() {
        String sql = "SELECT file_name, file_source FROM file_config " +
                "WHERE process_code = ? AND DATE_FORMAT(create_at_file, '%d%m%Y') = ? AND status = 'RD'";
        // Khởi tạo List để lưu kết quả
        List<String[]> fileList = new ArrayList<>();
        try (PreparedStatement pstmt = controlConn.prepareStatement(sql)) {
            pstmt.setInt(1, PROCESS_CODE);
            pstmt.setString(2, this.date);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String fileName = rs.getString("file_name");
                    String fileSource = rs.getString("file_source");
                    System.out.println(fileName + " " + fileSource);
                    // Thêm cặp giá trị [file_name, file_source] vào List
                    fileList.add(new String[]{fileName, fileSource});
                }
            }
            // Xử lý kết quả sau khi lặp
            if (!fileList.isEmpty()) {
                System.out.println("Đã tìm thấy " + fileList.size() + " record thành công trước đó.");
                return fileList;
            } else {
                System.out.println("Chưa có record thành công nào được tìm thấy.");
                System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Record trước đó chưa chạy thành công", pathFileError));
                System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Record trước đó chưa chạy thành công", "FL"));
                return fileList; // Trả về list rỗng
            }
        } catch (SQLException e) {
            // Xử lý lỗi SQL
            System.out.println("Ghi log error_log thành công" + insertLogToErrorLog("Lỗi khi kiểm tra etl_log", pathFileError));
            System.out.println("Ghi log etl_log thành công" + insertLogToETLLog("Lỗi khi kiểm tra etl_log", "FL"));
            throw new RuntimeException("Lỗi kiểm tra etl_log: ", e);
        }
    }

    public boolean loadToDBMart() {
        List<String[]> filesToLoad = loadOrder();
        if (filesToLoad == null || filesToLoad.isEmpty()) {
            System.out.println("Không có file nào để nạp vào Data Mart.");
            return false;
        }
        try {
            connectDBDT(DB_MART_URL, "Mart");
            dataConn.setAutoCommit(false);
            int totalRecordsLoaded = 0;

            // LOAD LẦN LƯỢT TỪNG FILE
            for (String[] fileInfo : filesToLoad) {
                String tableName = fileInfo[0]; // Tên bảng đích (Ví dụ: dim_brand)
                String filePath = fileInfo[1];  // Đường dẫn file

                int recordsLoaded = loadFileToTable(tableName, filePath);
                totalRecordsLoaded += recordsLoaded;

                if (recordsLoaded > 0) {
                    System.out.println("   Hoàn tất nạp " + recordsLoaded + " dòng cho " + tableName + ". Cập nhật status SC.");
                    insertLogToFileConfig(tableName, filePath, "SC");
                } else if (recordsLoaded == 0) {
                    System.out.println("   Hoàn tất nạp 0 dòng cho " + tableName + ". Cập nhật status SC.");
                    insertLogToFileConfig(tableName, filePath, "SC");
                } else { // recordsLoaded == -1 (Lỗi)
                    throw new RuntimeException("File " + filePath + " không có dữ liệu hoặc lỗi trong quá trình tải SQL.");
                }
            }
            dataConn.commit();
            insertLogToETLLog("Load Mart hoàn tất", "SC");
            System.out.println("Load Data Mart hoàn tất! Tổng số bản ghi được nạp: " + totalRecordsLoaded);
            return true;
        } catch (Exception e) {
            try {
                // Rollback toàn bộ nếu có bất kỳ lỗi nào xảy ra trong chuỗi Load
                if (dataConn != null) dataConn.rollback();
            } catch (SQLException ex) {
                System.err.println("Lỗi Rollback: " + ex.getMessage());
            }

            String errorMessage = "Lỗi trong quá trình Load Mart: " + e.getMessage();
            System.err.println(errorMessage);
            insertLogToErrorLog(errorMessage, pathFileError);
            insertLogToETLLog("Lỗi Load Mart", "FL");
            return false;
        }
    }

    public int loadFileToTable(String tableName, String csvPath) throws SQLException {
        // Tên bảng tạm và bảng backup
        String tempTable = tableName + "_tmp";
        String backupTable = tableName + "_bak";
        System.out.println("\n LOAD TABLE: " + tableName);
        System.out.println("CSV: " + csvPath);

        File f = new File(csvPath);
        if (!f.exists()) {
            System.err.println("Không tìm thấy file CSV: " + csvPath);
            insertLogToFileConfig("mart_load " + tableName, csvPath, "FL");
            return -1; // Trả về -1 nếu lỗi
        }
        try {
            // 1. Tạo bảng tạm
            try (Statement st = dataConn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + tempTable);
                st.execute("CREATE TABLE " + tempTable + " LIKE " + tableName);
                System.out.println("Tạo bảng tạm: " + tempTable);
            }

            // 2. Lấy số cột
            int colCount;
            try (Statement st = dataConn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM " + tableName + " LIMIT 1")) {
                colCount = rs.getMetaData().getColumnCount();
                System.out.println("→ Số cột bảng DIM: " + colCount);
            }

            // 3. Build và Load CSV vào bảng tạm
            String insertSQL = "INSERT INTO " + tempTable + " VALUES (" +
                    "?,".repeat(colCount - 1) + "?)";

            int rowNum = 0;
            try (CSVReader reader = new CSVReader(new FileReader(csvPath));
                 PreparedStatement ps = dataConn.prepareStatement(insertSQL)) {

                reader.readNext(); // BỎ HEADER
                System.out.println("→ Bắt đầu INSERT batch CSV...");
                String[] row;
                while ((row = reader.readNext()) != null) {
                    rowNum++;
                    // Kiểm tra đảm bảo số cột khớp
                    if (row.length != colCount) {
                        System.err.println("Lỗi: Số cột dòng " + rowNum + " không khớp (" + row.length + " vs " + colCount + ")");
                        throw new RuntimeException("Số cột không khớp");
                    }
                    for (int i = 0; i < colCount; i++) {
                        ps.setString(i + 1, row[i]);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
                System.out.println("Số dòng đã load vào bảng tạm: " + rowNum);
            }

            // 4. Swap bảng (RENAME)
            try (Statement st = dataConn.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                System.out.println("Xoá bảng backup cũ (nếu có)");
                st.execute("DROP TABLE IF EXISTS " + backupTable);
                System.out.println("Đổi tên bảng " + tableName + " → " + backupTable);
                st.execute("RENAME TABLE " + tableName + " TO " + backupTable);
                System.out.println("Đổi tên bảng tạm → bảng chính");
                st.execute("RENAME TABLE " + tempTable + " TO " + tableName);
                // Xoá bảng backup
                System.out.println("Xoá bảng bak");
                st.execute("DROP TABLE IF EXISTS " + backupTable);
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            System.out.println("LOAD DIM DONE: " + tableName);
            return rowNum; // Trả về số dòng đã load

        } catch (Exception e) {
            // Rollback cục bộ (các thay đổi RENAME chưa được commit sẽ bị Rollback)
            try {
                dataConn.rollback();
            } catch (Exception ignore) {
            }
            e.printStackTrace();
            System.err.println(" LỖI LOAD TABLE: " + tableName);
            insertLogToFileConfig("wh_load " + tableName, csvPath, "FL");
            return -1;
        }
    }

    //Insert record mới vào trong file_config
    public boolean insertLogToFileConfig(String fileName, String pathFile, String status) {
        String sql = "INSERT INTO file_config (process_code, file_source, create_at_file, status, file_name) " +
                "VALUES (?, ?,NOW(),?,?) ";

        try (PreparedStatement pstmt = controlConn.prepareStatement(sql)) {
            pstmt.setInt(1, PROCESS_CODE);
            pstmt.setString(2, pathFile);
            pstmt.setString(3, status);
            pstmt.setString(4, fileName);
            int affectedRows = pstmt.executeUpdate();

            return affectedRows > 0;
        } catch (SQLException e) {
            System.out.println("Ghi log error_log thành công " + insertLogToErrorLog("Lỗi khi insert log vào file_config", "N/A"));
            System.out.println(e.getMessage());
            return false;
        }
    }

    //Insert record mới vào trong etl_log
    public boolean insertLogToETLLog(String mess, String status) {
        String sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) " +
                "VALUES (?, NOW(),?,?) ";

        try (PreparedStatement pstmt = controlConn.prepareStatement(sql)) {
            pstmt.setInt(1, PROCESS_CODE);
            pstmt.setString(2, status);
            pstmt.setString(3, mess);
            int affectedRows = pstmt.executeUpdate();

            return affectedRows > 0;
        } catch (SQLException e) {
            System.out.println("Ghi log error_log thành công " + insertLogToErrorLog("Lỗi khi insert log vào etl_log", "N/A"));
            System.out.println(e.getMessage());
            return false;
        }
    }

    //Insert record mới vào trong error_log
    public boolean insertLogToErrorLog(String title, String pathFileError) {
        String sql = "INSERT INTO error_log (process_code, error_time, error_message, error_file) " +
                "VALUES (?, NOW(),?,?) ";

        try (PreparedStatement pstmt = controlConn.prepareStatement(sql)) {
            pstmt.setInt(1, PROCESS_CODE);
            pstmt.setString(2, title);
            pstmt.setString(3, pathFileError);
            int affectedRows = pstmt.executeUpdate();

            return affectedRows > 0;
        } catch (SQLException e) {
            System.out.println("Ghi log error_log thành công " + insertLogToErrorLog("Lỗi khi insert log vào error_log", "N/A"));
            System.out.println(e.getMessage());
            return false;
        }
    }

    // Đóng kết nối control khi đã hoàn thành
    public void closeConnectionControl() throws SQLException {
        controlConn.close();
    }

    // Đóng kết nối db cần sử dụng khi đã hoàn thành
    public void closeConnectionDB() throws SQLException {
        dataConn.close();
    }

    public static void main(String[] args) throws SQLException {
        LoadDatamart main = new LoadDatamart();

        String defaultConfigPath = "/DW/control/config.xml";
//        String defaultConfigPath = "D:\\ki1nam4\\DW\\Script\\DW-Crawl\\config.xml";
        String targetDate = null;
        String configFilePath;

        List<String> tablesToExport = List.of("aggregate_gold", "dim_brand", "dim_location", "dim_date");
        if (args.length >= 2) {
            // Chạy tay: java -jar /DW/scripts/load_datamart.jar /DW/control/config.xml
            configFilePath = args[0];
            targetDate = args[1];
            System.out.println("Nhận tham số thủ công: Config=" + configFilePath + ", Date=" + targetDate);
        } else {
            // Lập lịch: Sử dụng giá trị mặc định
            configFilePath = defaultConfigPath;
            System.out.println("Chạy tự động: Sử dụng Config mặc định và Ngày hiện tại.");
        }

        try {
            main.loadConfig(configFilePath, targetDate);
            main.connectDBControl();
            main.connectDBDT(main.DB_WAREHOUSE_URL, "WH");
            main.exportTablesToCSV(tablesToExport);

            main.loadToDBMart();
        } catch (Exception e) {
            System.err.println("\n--- LỖI TRONG QUÁ TRÌNH THỰC THI ---");
            e.printStackTrace();
        } finally {
            System.out.println("\n====== ĐÓNG KẾT NỐI ======");
            if (main.dataConn != null) main.closeConnectionDB();
            if (main.controlConn != null) main.closeConnectionControl();
            System.out.println("Đã đóng kết nối thành công.");
        }
    }
}