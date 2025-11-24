package com.example.dwcrawl.Staging;

import com.opencsv.CSVReader;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class LoadToWarehouse {
    private String USER_AGENT;
    private int TIMEOUT_MS;
    private String FILE_PATH_TO_WH;
    private String DB_STAGING;
    private String DB_CONTROL;
    private String DB_WAREHOUSE;
    private String DB_USER;
    private String DB_PASS;
    private Connection connControl, connStaging, connWarehouse;
    private final int PROCESS_CODE = 6;
    private static final String errorFilePath = "/DW/control/config_error/";


    public static void main(String[] args) {
        LoadToWarehouse loadtowh = new LoadToWarehouse();
        // 1. Load config: đọc file cấu hình config XML để lấy thông tin cấu hình
        loadtowh.loadConfig();
        try {
            //2. Kết nối các database control, staging, warehouse
            loadtowh.connectDatabases();

            // 3. Kiểm tra etl_log trạng thái Process 5 (Transform) với status = "SU"
            if (loadtowh.checkStatusProcess()) {
                System.out.println("Process 5 (Transform) hôm nay đã run thành công. Tiếp tục Process 6 (Load To Warehouse)");
                loadtowh.insertLogToETLLog("Process 6 Load Warehouse đang chạy ", "PS");
                try {
                    // 4.Xử lý và đối chiếu dữ liệu bảng clean và PreWH (Staging)
                    loadtowh.processCleanToPreWH();

                    // 5  Xuất dữ liệu bảng prewh, dim_brand, dim_location, dim_date (staging) ra CSV lưu vào "/DW/staging/export/"
                    String filePathBrand = loadtowh.exportToCSV(loadtowh.connStaging, "dim_brand");
                    loadtowh.insertLogToFileConfig("stg_export dim_brand", filePathBrand, "RD");

                    String filePathLocation = loadtowh.exportToCSV(loadtowh.connStaging, "dim_location");
                    loadtowh.insertLogToFileConfig("stg_export dim_location", filePathLocation, "RD");

                    String filePathDate = loadtowh.exportToCSV(loadtowh.connStaging, "dim_date");
                    loadtowh.insertLogToFileConfig("stg_export dim_date", filePathDate, "RD");

                    String filePathPreWH = loadtowh.exportToCSV(loadtowh.connStaging, "stg_gold_price_prewh");
                    loadtowh.insertLogToFileConfig("stg_export stg_gold_price_prewh", filePathPreWH, "RD");

                    // 6. Load, đọc file csv vào bảng trong database warehouse
                        // Bảng dim_brand (db warehouse)
                    loadtowh.insertLogToFileConfig("wh_load dim_brand", filePathBrand, "RN");
                    try {
                        loadtowh.loadDimTable("dim_brand", filePathBrand);
                        loadtowh.insertLogToFileConfig("wh_load dim_brand", filePathBrand, "SC");
                    } catch (RuntimeException e) {
                        loadtowh.insertLogToFileConfig("wh_load dim_brand", filePathBrand, "FL");
//                        loadtowh.insertLogToErrorLog("Lỗi nạp dữ liệu vào dim_brand", filePathBrand);
                        throw e;
                    }

                        // Bảng dim_location (db warehouse)
                    loadtowh.insertLogToFileConfig("wh_load dim_location", filePathLocation, "RN");
                    try {
                        loadtowh.loadDimTable("dim_location", filePathLocation);
                        loadtowh.insertLogToFileConfig("wh_load dim_location", filePathLocation, "SC");
                    } catch (RuntimeException e) {
                        loadtowh.insertLogToFileConfig("wh_load dim_location", filePathLocation, "FL");
//                        loadtowh.insertLogToErrorLog("Lỗi nạp dữ liệu vào dim_location", filePathLocation);
                        throw e;
                    }

                        // Bảng dim_date (db warehouse)
                    loadtowh.insertLogToFileConfig("wh_load dim_date", filePathDate, "RN");
                    try {
                        loadtowh.loadDimTable("dim_date", filePathDate);
                        loadtowh.insertLogToFileConfig("wh_load dim_date", filePathDate, "SC");
                    } catch (RuntimeException e) {
                        loadtowh.insertLogToFileConfig("wh_load dim_date", filePathDate, "FL");
//                        loadtowh.insertLogToErrorLog("Lỗi nạp dữ liệu vào dim_date", filePathDate);
                        throw e;
                    }

                        // Bảng gold_price (db warehouse)
                    loadtowh.insertLogToFileConfig("wh_load gold_price", filePathPreWH, "RN");
                    try {
                        loadtowh.loadGoldPrice("gold_price", filePathPreWH);
                        loadtowh.insertLogToFileConfig("wh_load gold_price", filePathPreWH, "SC");
                    } catch (RuntimeException e) {
                        loadtowh.insertLogToFileConfig("wh_load gold_price", filePathPreWH, "FL");
//                        loadtowh.insertLogToErrorLog("Lỗi nạp dữ liệu vào gold_price", filePathPreWH);
                        throw e;
                    }
                    System.out.println("\n===== KẾT THÚC LOAD WAREHOUSE =====");
                    loadtowh.insertLogToETLLog("Process 6 Load Warehouse thành công ", "SC");

                } catch (Exception er) {
                    loadtowh.insertLogToETLLog("Process 6 Load Warehouse thất bại ", "FL");
                    System.err.println("FATAL in Process 6: " + er.getMessage());
                    er.printStackTrace();
                    System.exit(1);
                }
            } else {
                //  Kiển tra status etl_log lỗi
                System.err.println("Process 5 (Transform) hôm nay không thành công. Dừng process");
                loadtowh.insertLogToETLLog("Process 6 Load Warehouse thất bại ", "FL");
                loadtowh.insertLogToErrorLog("Process 5 Transform chưa thành công → dừng Process 6 Load Warehouse", "LoadToWarehouse.jar");

            }
        } catch (Exception e) {
            System.out.println("Lỗi process: " + e.getMessage());
            e.printStackTrace();
//            loadtowh.insertLogToETLLog("Process 6 Load Warehouse thất bại ", "FL");
            System.exit(1);
        } finally {
            // 7. Đóng kết nối các database
            loadtowh.closeConnections();
        }

    }

    // 1. Load config: đọc file cấu hình config XML để lấy thông tin cấu hình
    public void loadConfig() {
        File configFile = new File("/DW/control/config.xml");

        // Ngày hiện tại theo định dạng ddMMyyyy
        String currentDate = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"));

        if (!configFile.exists()) {
            File errorFile = new File(errorFilePath + "process6_" + currentDate + "_load-config-config-error.txt");
            try (FileWriter writer = new FileWriter(errorFile)) {
                writer.write("Không tìm thấy file config.xml");
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không tìm thấy file config.xml");
        }
        // File config.xml tồn tại thì đọc, nhưng lỗi khi đọc hoặc thiếu giá trị
        try (InputStream input = new FileInputStream(configFile)) {
            java.util.Properties props = new java.util.Properties();
            // đọc file XML theo định dạng Properties
            props.loadFromXML(input);

            FILE_PATH_TO_WH = getRequiredProperty(props, "stg_to_wh.path", "FILE_PATH_TO_WH");
            USER_AGENT = getRequiredProperty(props, "user.agent", "USER_AGENT");
            TIMEOUT_MS = Integer.parseInt(getRequiredProperty(props, "timeout.ms", "TIMEOUT_MS"));
            DB_USER = getRequiredProperty(props, "db_user_root_name", "DB_USER");
            DB_PASS = getRequiredProperty(props, "db_user_root_pass", "DB_PASS");
            DB_STAGING = getRequiredProperty(props, "db_staging", "DB_STAGING");
            DB_CONTROL = getRequiredProperty(props, "db_control", "DB_CONTROL");
            DB_WAREHOUSE = getRequiredProperty(props, "db_warehouse", "DB_WAREHOUSE");

            System.out.println("User Agent: " + USER_AGENT);
            System.out.println("Timeout: " + TIMEOUT_MS);
            System.out.println("DB Warehouse: " + DB_WAREHOUSE);
            System.out.println("DB Staging: " + DB_STAGING);
            System.out.println("DB Control: " + DB_CONTROL);
            System.out.println("OUT_PUT: " + FILE_PATH_TO_WH);
        } catch (Exception e) {
            // Ghi lỗi vào file nếu không đọc được config hoặc thiếu giá trị
            File errorFile = new File(errorFilePath + "process6_" + currentDate + "_load-config-read-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(errorFile))) {
                writer.write("Lỗi đọc file config.xml cho Process 6: " + e.getMessage());
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Lỗi load config.xml: " + e.getMessage(), e);
        }
    }
    private static String getRequiredProperty(Properties props, String key, String name) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new RuntimeException("Giá trị config " + name + " không được null hoặc rỗng");
        }
        return value;
    }

    //2. Kết nối các database control, staging, warehouse
    public void connectDatabases() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connControl = DriverManager.getConnection(DB_CONTROL, DB_USER, DB_PASS);
            System.out.println("Kết nối db CONTROL thành công");
            connStaging = DriverManager.getConnection(DB_STAGING, DB_USER, DB_PASS);
            System.out.println("Kết nối db STAGING thành công");
            connWarehouse = DriverManager.getConnection(DB_WAREHOUSE, DB_USER, DB_PASS);
            System.out.println("Kết nối db WAREOUSE thành công");
        } catch (Exception e) {
            System.out.println("Error connect database: " + e.getMessage());
            throw new RuntimeException("Cannot connect databases", e);
        }
    }

    // 3. Kiểm tra etl_log trạng thái Process 5 (Transform) với status = "SU"
    public boolean checkStatusProcess() {
        //Lấy bản ghi ETL mới nhất của Process 5 – Transform, chạy trong ngày hôm nay.
        String sql = """ 
                SELECT el.status, cs.process_name FROM etl_log el
                                 JOIN config_source cs ON cs.process_code = el.process_code
                WHERE el.process_code=5 AND cs.process_name = 'Transform' AND DATE(el.run_date) = CURDATE()
                ORDER BY el.run_date DESC LIMIT 1;
                """;
        //thực thi câu lệnh SQL có tham số (?)
        try (PreparedStatement ps = connControl.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
                //nếu có bản ghi log trong ngày hôm nay.
            if (rs.next()) {
                // Lấy ra
                String status = rs.getString("status");
                String name = rs.getString("process_name");

                System.out.println("processName" + name + ", Status= " + status);

                return "SC".equals(status);
                // Cho phép chạy process 6
            }
            return false; // chưa có bản ghi log nào
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 4.Xử lý và đối chiếu dữ liệu bảng clean và PreWH (Staging)
    public void processCleanToPreWH() {
        try {
                //Tắt Auto-commit để bắt đầu Transaction
            connStaging.setAutoCommit(false);
                //Lấy toàn bộ dữ liệu đã clean từ bảng staging
            String sql = "SELECT record_id,brand,location, gold_type,buy_price,sell_price, unit,source, timestamp FROM stg_gold_price_clean";

            try (PreparedStatement ps = connStaging.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                //Duyệt từng dòng trong bảng clean được map sang DIM và FACT.
                while (rs.next()) {
                        //các field của dữ liệu
                    String recordId = rs.getString("record_id");
                    String brand = rs.getString("brand");
                    String location = rs.getString("location");
                    String goldType = rs.getString("gold_type");
                    double buyPrice = rs.getDouble("buy_price");
                    double sellPrice = rs.getDouble("sell_price");
                    String unit = rs.getString("unit");
                    String source = rs.getString("source");
                    java.sql.Timestamp times = rs.getTimestamp("timestamp");
                    //Mapping sang bảng DIM
                        // dim_brand
                    int brandSK = getBrandSK(brand);
                        //  dim_location
                    int locationSK = getLocationSK(location);
                        //  dim_date
                    int dateId = getDateId(times);

                    //Insert hoặc Update prewarehouse
                    processFactPreWH(recordId, brandSK, locationSK, goldType,
                            dateId, buyPrice, sellPrice, unit, source, times);
                }
            }
            //Commit transaction
            connStaging.commit();
            System.out.println("Đã thực hiện so sánh, insert, update vào prewh, dim_brand, dim_location, dim_date");
        } catch (Exception e) {
            try {
                //Bắt lỗi và rollback
                connStaging.rollback();
                System.err.println("Lỗi phát sinh, rollback data: " + e.getMessage());
            } catch (Exception ex) {
                System.err.println("Lỗi rollback: " + ex.getMessage());
            }
            //Ném error Để hàm main biết process fail
            throw new RuntimeException("FATAL: processCleanToPreWH failed", e);
        } finally {
            // Tắt autoCommit
            try {
                //Bật lại Auto-commit
                connStaging.setAutoCommit(true);
            } catch (Exception ignore) {
            }
        }
    }
        // Đối chiếu hoặc thêm brand vào dim_brand
    private int getBrandSK(String brandName) throws Exception {
        PreparedStatement ps = null, psNext = null, ps2 = null;
        ResultSet rs = null, rsNext = null, rs2 = null;
        try {
            // Check xem brand đã tồn tại hay chưa
            String check = "SELECT sk FROM dim_brand WHERE brand_name = ? AND date_expired = '9999-12-31'";
            ps = connStaging.prepareStatement(check);
            ps.setString(1, brandName);
            rs = ps.executeQuery();
                // Nếu tồn tại thì lấy "sk"
            if (rs.next()) {
                return rs.getInt("sk");
            }
            // Nếu chưa tồn tại -> Tạo id mới → MAX(id) + 1
            String nextIdSql = "SELECT COALESCE(MAX(id), 0) + 1 FROM dim_brand";
            psNext = connStaging.prepareStatement(nextIdSql);
            rsNext = psNext.executeQuery();
            rsNext.next();
            int newId = rsNext.getInt(1);

            // Insert dòng mới
            String insert = "INSERT INTO dim_brand (id, brand_name, date_expired) VALUES (?, ?, '9999-12-31')";
            ps2 = connStaging.prepareStatement(insert, PreparedStatement.RETURN_GENERATED_KEYS);
            ps2.setInt(1, newId);
            ps2.setString(2, brandName);
            ps2.executeUpdate();

            // Lấy sk được tạo ra
            rs2 = ps2.getGeneratedKeys();
            if (rs2.next()) {
                return rs2.getInt(1);
            }
            throw new Exception("Insert dim_brand không thành công");
        // finally → đóng tài nguyên tránh leak connection.
        } finally {
            if (rs != null) rs.close();
            if (rsNext != null) rsNext.close();
            if (rs2 != null) rs2.close();
            if (ps != null) ps.close();
            if (psNext != null) psNext.close();
            if (ps2 != null) ps2.close();
        }
    }

    // Đối chiếu hoặc thêm location vào dim_location
    private int getLocationSK(String location) throws Exception {
        PreparedStatement ps = null, psMax = null, ps3 = null;
        ResultSet rs = null, rsMax = null, g = null;
        try {
            //Check xem location đã tồn tại hay chưa
            String check = "SELECT sk FROM dim_location WHERE city_name = ? AND date_expired = '9999-12-31'";
            ps = connStaging.prepareStatement(check);
            ps.setString(1, location);
            rs = ps.executeQuery();

            // Nếu tồn tại thì lấy "sk"
            if (rs.next()) return rs.getInt("sk");

            // Nếu chưa tồn tại -> Tạo id mới → MAX(id) + 1
            String getMax = "SELECT COALESCE(MAX(location_id), 0) + 1 AS next_id FROM dim_location";
            psMax = connStaging.prepareStatement(getMax);
            rsMax = psMax.executeQuery();
            rsMax.next();
            int nextId = rsMax.getInt("next_id");

            // Insert dòng mới
            String insert = "INSERT INTO dim_location (location_id, city_name, date_expired) VALUES (?,?, '9999-12-31')";
            ps3 = connStaging.prepareStatement(insert, PreparedStatement.RETURN_GENERATED_KEYS);
            ps3.setInt(1, nextId);
            ps3.setString(2, location);
            ps3.executeUpdate();

            // Lấy sk được tạo ra
            g = ps3.getGeneratedKeys();
            if (g.next()) return g.getInt(1);

            throw new Exception("Insert dim_location không thành công");
        } finally {
            // finally → đóng tài nguyên tránh leak connection.
            if (rs != null) rs.close();
            if (rsMax != null) rsMax.close();
            if (g != null) g.close();
            if (ps != null) ps.close();
            if (psMax != null) psMax.close();
            if (ps3 != null) ps3.close();
        }
    }

    // Đối chiếu hoặc thêm location vào dim_location
    private int getDateId(java.sql.Timestamp ts) throws Exception {
        PreparedStatement ps = null, ps4 = null;
        ResultSet rs = null, gen = null;
        try {
            //Convert timestamp
            String dateString = ts.toLocalDateTime().toLocalDate().toString();

            //Check xem date đã tồn tại trong dim hay chưa
            String check = "SELECT date_id FROM dim_date WHERE date_value = ?";
            ps = connStaging.prepareStatement(check);
            ps.setString(1, dateString);
            rs = ps.executeQuery();

            // Nếu tồn tại thì lấy "date_id"
            if (rs.next()) return rs.getInt("date_id");

            // Nếu chưa có → tạo mới
            java.time.LocalDate d = ts.toLocalDateTime().toLocalDate();
            String insert = """
                    INSERT INTO dim_date (date_value, day, month, year, quarter)
                    VALUES (?, ?, ?, ?, ?)
                    """;

            ps4 = connStaging.prepareStatement(insert, PreparedStatement.RETURN_GENERATED_KEYS);
            ps4.setString(1, dateString);
            ps4.setInt(2, d.getDayOfMonth());
            ps4.setInt(3, d.getMonthValue());
            ps4.setInt(4, d.getYear());
            ps4.setInt(5, (d.getMonthValue() - 1) / 3 + 1);
            ps4.executeUpdate();

            // Lấy id được tạo ra
            gen = ps4.getGeneratedKeys();
            if (gen.next()) return gen.getInt(1);

            throw new Exception("Insert dim_date không thành công");
        } finally {
            // finally → đóng tài nguyên tránh leak connection.
            if (rs != null) rs.close();
            if (gen != null) gen.close();
            if (ps != null) ps.close();
            if (ps4 != null) ps4.close();
        }

    }

    // Đối chiếu hoặc thêm các hàng trong prewh có thay đổi giá chưa.
    private void processFactPreWH(String id, int brandSK, int locationSK,
                                  String goldType, int dateId,
                                  double buy, double sell, String unit, String source,
                                  java.sql.Timestamp ts) throws Exception {

        // Kiểm tra record hiện tại
        String checkOld = """
                SELECT buy_price, sell_price FROM stg_gold_price_prewh
                WHERE id = ? AND expire_date = '9999-12-31'
                """;

        PreparedStatement ps = connStaging.prepareStatement(checkOld);
        ps.setString(1, id);
        ResultSet rs = ps.executeQuery();

        //Nếu chưa có record → insert mới
        if (!rs.next()) {
            // Chưa có record → insert luôn
            insertNewPreWH(id, brandSK, locationSK, goldType, dateId, buy, sell, unit, source, ts);
            return;
        }
        // Nếu tồn tại thì lấy giá ra đối chiếu
        double oldBuy = rs.getDouble("buy_price");
        double oldSell = rs.getDouble("sell_price");

        // Nếu giá không đổi → bỏ qua
        if (oldBuy == buy && oldSell == sell) {
            System.out.println(" ID dòng Không thay đổi = " + id);
            return;
        }

        // Giá thay đổi → update dòng old + insert dòng new
        System.out.println(" ID bị thay đổi = " + id);
        //Update record cũ → hết hiệu lực hôm nay
        String updateOld = """
                UPDATE stg_gold_price_prewh
                SET expire_date = CURDATE()
                WHERE id = ? AND expire_date = '9999-12-31'
                """;

        PreparedStatement ps2 = connStaging.prepareStatement(updateOld);
        ps2.setString(1, id);
        ps2.executeUpdate();

        // Insert dòng mới
        insertNewPreWH(id, brandSK, locationSK, goldType, dateId, buy, sell, unit, source, ts);
    }

    // Hàm thêm dòng mới
    private void insertNewPreWH(String id, int brandSK, int locationSK,
                                String goldType, int dateId, double buy, double sell,
                                String unit, String source, java.sql.Timestamp ts) throws Exception {

//        java.sql.Timestamp loadTs = new java.sql.Timestamp(System.currentTimeMillis());

        String insert = """
                INSERT INTO stg_gold_price_prewh
                (id, brand_sk, location_sk, gold_type, date_id,
                 buy_price, sell_price, unit, source, load_date, expire_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), '9999-12-31')
                """;

        try (PreparedStatement ps = connStaging.prepareStatement(insert)) {
            ps.setString(1, id);
            ps.setInt(2, brandSK);
            ps.setInt(3, locationSK);
            ps.setString(4, goldType);
            ps.setInt(5, dateId);
            ps.setDouble(6, buy);
            ps.setDouble(7, sell);
            ps.setString(8, unit);
            ps.setString(9, source);
//            ps.setTimestamp(10, loadTs);

            ps.executeUpdate();
            System.out.println("Inserted thêm dòng vào prewh: " + id);
        }
    }


    // ExportToCSV để xử lý các trường hợp:giá trị có dấu phẩy, ,có dấu xuống dòng, có ký tự đặc biệt → bọc bằng ".
    private static String escapeCsv(String value) {
        if (value == null) return "";
        value = value.replace("\"", "\"\""); // escape dấu "
        return "\"" + value + "\""; // bao quanh bởi "
    }

    //5. Xuất dữ liệu bảng prewh, dim_brand, dim_location, dim_date (staging) ra CSV lưu vào "/DW/staging/export/"
    public String exportToCSV(Connection conn, String tableName) {
        // Ngày hiện tại
        String dateString = java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy")
        );
        // Tạo tên file
        String outputFile = FILE_PATH_TO_WH + File.separator + tableName + "_" + dateString + ".csv";
        // Lấy toàn bộ bảng
        String sql = "SELECT * FROM " + tableName;

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery();
             java.io.FileWriter writer = new java.io.FileWriter(outputFile)) {

            //Lấy thông tin metadata của bảng
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            // xác định ds các cột cần ghi (bỏ load_date)
            List<Integer> exportColumns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                String colName = meta.getColumnName(i);
                if (!colName.equalsIgnoreCase("load_date")) {
                    exportColumns.add(i);
                }
            }

            // Ghi  header vào file CSV
            for (int i = 0; i < exportColumns.size(); i++) {
                writer.append(escapeCsv(meta.getColumnName(exportColumns.get(i))));
                if (i < exportColumns.size() - 1) writer.append(",");
            }
            writer.append("\n");

            // Ghi từng dòng dữ liệu vào CSV.
            while (rs.next()) {
                for (int i = 0; i < exportColumns.size(); i++) {
                    String val = rs.getString(exportColumns.get(i));
                    writer.append(escapeCsv(val));
                    if (i < exportColumns.size() - 1) writer.append(",");
                }
                writer.append("\n");
            }

            System.out.println("Xuất CSV thành công: " + outputFile);
            return outputFile;

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Lỗi export CSV: " + e.getMessage());
            insertLogToErrorLog("Lỗi export file CSV của bảng :" + tableName, outputFile);
            return null;
        }
    }


    // 6. Load, đọc file csv vào bảng trong database warehouse
        //// Bảng DIM
    public boolean loadDimTable(String tableName, String csvPath) {
        String tempTable = tableName + "_tmp";
        String backupTable = tableName + "_bak";
        System.out.println(" LOAD DIM TABLE: " + tableName);
        System.out.println("CSV: " + csvPath);

        //Kiểm tra file CSV có tồn tại hay không
        File f = new File(csvPath);
        if (!f.exists()) {
            System.err.println("Không tìm thấy file CSV: " + csvPath);
            insertLogToFileConfig(" Lỗi không tìm thấy file wh_load " + tableName, csvPath, "FL");
            insertLogToErrorLog("Lỗi không tìm thấy file CSV khi để load vào wh DIM: " + tableName, csvPath);
            throw new RuntimeException("File CSV không tồn tại: " + csvPath);
        }

        try {
            //Tắt auto-commit để chạy transaction lớn
            connWarehouse.setAutoCommit(false);

            // Tạo bảng tạm (_tmp)
            long t1 = System.currentTimeMillis();
            try (Statement st = connWarehouse.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + tempTable);
                st.execute("CREATE TABLE " + tempTable + " LIKE " + tableName);
                System.out.println("Tạo bảng tạm: " + tempTable);
            }

            // Lấy số cột
            int colCount;
            try (Statement st = connWarehouse.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM " + tableName + " LIMIT 1")) {
                colCount = rs.getMetaData().getColumnCount();
                System.out.println("→ Số cột bảng DIM: " + colCount);
            }

            // Build câu INSERT dynamic ,Dựa trên số cột tạo n dấu ?.
            String insertSQL = "INSERT INTO " + tempTable + " VALUES (" +
                    "?,".repeat(colCount - 1) + "?)";

            try (CSVReader reader = new CSVReader(new FileReader(csvPath));
                 PreparedStatement ps = connWarehouse.prepareStatement(insertSQL)) {

                //Đọc CSV và insert vào bảng tạm
                reader.readNext(); // BỎ HEADER
                System.out.println("→Bắt đầu INSERT batch CSV...");
                String[] row;
                int rowNum = 0;
                while ((row = reader.readNext()) != null) {
                    rowNum++;
                    try {
                        for (int i = 0; i < colCount; i++) {
                            ps.setString(i + 1, row[i]);
                        }
                        ps.addBatch();

                    } catch (Exception exRow) {
                        insertLogToErrorLog("Lỗi khi đọc file csv: " + tableName + "|Dòng: " + rowNum + "- Data: " + Arrays.toString(row), csvPath);
                        throw new RuntimeException("Lỗi khi insert dữ liệu dim: " + exRow.getMessage(), exRow);
                    }
                }
                ps.executeBatch();
                System.out.println("Số dòng đã load vào bảng tạm: " + rowNum);
            }

            // Swap bảng
            try (Statement st = connWarehouse.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");

                System.out.println("Xoá bảng backup cũ (nếu có)");
                st.execute("DROP TABLE IF EXISTS " + backupTable);

                System.out.println("Đổi tên bảng " + tableName + " → " + backupTable);
                st.execute("RENAME TABLE " + tableName + " TO " + backupTable);

                System.out.println("Đổi tên bảng tạm → bảng chính");
                st.execute("RENAME TABLE " + tempTable + " TO " + tableName);

                // Xoá bảng backup để không còn rác
                System.out.println("Xoá bảng bak");
                st.execute("DROP TABLE IF EXISTS " + backupTable);

                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            //Commit
            connWarehouse.commit();
            System.out.println("LOAD DIM DONE: " + tableName);
            return true;

        } catch (Exception e) {
            try {
                //Nếu lỗi thì rollback và log
                connWarehouse.rollback();
            } catch (Exception ignore) {
            }
            System.err.println(" LỖI LOAD DIM TABLE: " + tableName);
            insertLogToErrorLog("Lỗi load_to_wh dữ liệu vào bảng DIM : " + tableName, csvPath);
            throw new RuntimeException("FATAL: loadDimTable failed for " + tableName, e);
        }
    }
            /// / Bảng gold_price
    public boolean loadGoldPrice(String tableName, String csvPath) {
        String tempTable = tableName + "_tmp";
        String backupTable = tableName + "_bak";

        System.out.println("LOAD FACT gold_price");
        System.out.println("CSV: " + csvPath);

        // Kiểm tra file CSV có tồn tại hay không
        File f = new File(csvPath);
        if (!f.exists()) {
            System.err.println(" Không tìm thấy file CSV: " + csvPath);
            insertLogToFileConfig(" Lỗi không tìm thấy file wh_load " + tableName, csvPath, "FL");
            insertLogToErrorLog("Lỗi không tìm thấy file CSV khi để load vào wh bảng : " + tableName, csvPath);
            throw new RuntimeException("File CSV không tồn tại: " + csvPath);
        }

        try {
            //Tắt auto-commit để chạy transaction lớn
            connWarehouse.setAutoCommit(false);

            // Tạo bảng tạm (_tmp)
            try (Statement st = connWarehouse.createStatement()) {
                System.out.println(" Xoá bảng tạm nếu tồn tại");
                st.execute("DROP TABLE IF EXISTS " + tempTable);

                System.out.println("Tạo bảng tạm LIKE bảng chính");
                st.execute("CREATE TABLE " + tempTable + " LIKE " + tableName);
            }

            //  Lấy danh sách cột
            List<String> columns = new ArrayList<>();
            try (Statement st = connWarehouse.createStatement();
                 ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + tableName)) {

                while (rs.next()) {
                    // bỏ qua các field
                    String col = rs.getString("Field");
//                    if (col.equals("sk")) continue;
                    if (col.equals("is_deleted")) continue;
                    if (col.equals("delete_date")) continue;
                    if (col.equals("load_date")) continue;

                    columns.add(col);
                }
            }

            System.out.println("Danh sách cột INSERT: " + columns);

            // Build INSERT dynamic có thêm load_date = NOW()
            String colList = String.join(",", columns) + ",load_date";
            String qMarks = "?,".repeat(columns.size()) + "NOW()";
            String insertSQL = "INSERT INTO " + tempTable + " (" + colList + ") VALUES (" + qMarks + ")";

            //Đọc CSV và insert vào bảng tạm
            try (CSVReader reader = new CSVReader(new FileReader(csvPath));
                 PreparedStatement ps = connWarehouse.prepareStatement(insertSQL)) {

                reader.readNext(); // bỏ header
                System.out.println(" Bắt đầu INSERT CSV vào gold_price_tmp");

                String[] row;
                int rowNum = 0;

                while ((row = reader.readNext()) != null) {
                    rowNum++;
                    try {
                        for (int i = 0; i < columns.size(); i++) {
                            ps.setString(i + 1, row[i]);
                        }
                        ps.addBatch();
                    } catch (Exception ex) {
                        insertLogToErrorLog("Lỗi khi đọc file csv: " + tableName + "|Dòng: " + rowNum + "- Data: " + Arrays.toString(row), csvPath);
                        throw new RuntimeException("Lỗi khi insert dữ liệu gold_price: " + ex.getMessage(), ex);   // dừng luôn toàn bộ load
                    }
                }


                ps.executeBatch();
                System.out.println(" Đã load " + rowNum + " dòng vào gold_price_tmp");
            }

            // Swap bảng
            try (Statement st = connWarehouse.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");

                System.out.println(" Xoá bảng backup cũ");
                st.execute("DROP TABLE IF EXISTS " + backupTable);

                System.out.println(" Đổi tên bảng chính → backup");
                st.execute("RENAME TABLE " + tableName + " TO " + backupTable);

                System.out.println(" Đổi tên bảng tạm → bảng chính");
                st.execute("RENAME TABLE " + tempTable + " TO " + tableName);

                // Xoá bảng backup
                System.out.println("Xoá bảng bak");
                st.execute("DROP TABLE IF EXISTS " + backupTable);

                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            // comit
            connWarehouse.commit();
            System.out.println(" LOAD FACT gold_price DONE");
            return true;

        } catch (Exception e) {
            try {
                //Nếu lỗi thì rollback và log
                connWarehouse.rollback();
            } catch (Exception ignore) {
            }
            System.err.println(" LỖI LOAD GOLD PRICE FACT");
            insertLogToErrorLog("Lỗi load_to_wh dữ liệu cho bảng bảng : " + tableName, csvPath);
//            return false;
            throw new RuntimeException("FATAL: loadGoldPrice failed for " + tableName, e);
        }
    }

    //LOG: Insert record mới vào trong file_config
    public boolean insertLogToFileConfig(String fileName, String pathFile, String status) {
        String sql = "INSERT INTO file_config (process_code, file_source, create_at_file, status, file_name) " +
                "VALUES (?, ?,NOW(),?,?) ";

        try (PreparedStatement pstmt = connControl.prepareStatement(sql)) {
            pstmt.setInt(1, PROCESS_CODE);
            pstmt.setString(2, pathFile);
            pstmt.setString(3, status);
            pstmt.setString(4, fileName);
            int affectedRows = pstmt.executeUpdate();

            return affectedRows > 0;
        } catch (SQLException e) {
            insertLogToErrorLog("Lỗi khi insert log vào file_config", "N/A");
            System.out.println("Lỗi khi insert log vào file_config");
            return false;
        }
    }

    //LOG: Insert record mới vào trong etl_log
    public boolean insertLogToETLLog(String mess, String status) {
        String sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) " +
                "VALUES (?, NOW(),?,?) ";

        try (PreparedStatement pstmt = connControl.prepareStatement(sql)) {
            pstmt.setInt(1, PROCESS_CODE);
            pstmt.setString(2, status);
            pstmt.setString(3, mess);
            int affectedRows = pstmt.executeUpdate();

            return affectedRows > 0;
        } catch (SQLException e) {
            System.out.println("Lỗi khi insert vào error_log. Không thể ghi thêm log. ");
            insertLogToErrorLog("Lỗi khi insert vào error_log. Không thể ghi thêm log.", "N/A");
            System.out.println(e.getMessage());
            return false;
        }
    }

    //LOG: Insert record mới vào trong error_log
    public boolean insertLogToErrorLog(String title, String pathFileError) {
        String sql = "INSERT INTO error_log (process_code, error_time, error_message, error_file) " +
                "VALUES (?, NOW(),?,?) ";

        try (PreparedStatement pstmt = connControl.prepareStatement(sql)) {
            pstmt.setInt(1, PROCESS_CODE);
            pstmt.setString(2, title);
            pstmt.setString(3, pathFileError);
            int affectedRows = pstmt.executeUpdate();

            return affectedRows > 0;
        } catch (SQLException e) {
            System.out.println("Lỗi khi insert log vào error_log : " + e.getMessage());
            insertLogToErrorLog("Lỗi khi insert log vào error_log", "N/A");
            return false;
        }
    }
// Đóng kết nối các database
    public void closeConnections() {
        try {
            if (connControl != null && !connControl.isClosed()) {
                connControl.close();
                System.out.println("Đóng kết nối CONTROL");
            }
            if (connStaging != null && !connStaging.isClosed()) {
                connStaging.close();
                System.out.println("Đóng kết nối STAGING");
            }
            if (connWarehouse != null && !connWarehouse.isClosed()) {
                connWarehouse.close();
                System.out.println("Đóng kết nối WAREHOUSE");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
