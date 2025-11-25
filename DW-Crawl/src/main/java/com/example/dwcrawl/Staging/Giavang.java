package com.example.dwcrawl.Staging;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

public class Giavang {
    private String URL;
    private String USER_AGENT;
    private int TIMEOUT_MS;
    private String outputPath;
    private static final String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
//    private static final String errorFilePath = "D:/DataWarehouse/DW-Crawl/";
    private static final String errorFilePath = "/DW/control/config_error/";
    private String config_file_path;
    private String DB_URL;
    private String USER;
    private String PASSWORD;

    public Connection connectToDatabase() {
        try {
            Connection conn = DriverManager.getConnection(DB_URL, USER, PASSWORD);
            System.out.println("Connected to MySQL successfully!");
            return conn;
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            return null;
        }
    }

    private static String getRequiredProperty(Properties props, String key, String name) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new RuntimeException("Giá trị config " + name + " không được null hoặc rỗng");
        }
        return value;
    }

    public void loadConfig() {
//        File configFile = new File("D:/DataWarehouse/DW-Crawl/config.xml");
        File configFile = new File(config_file_path);

        // Xuất file ddMMyyyy_source-crawl_config-error.txt
        // trong thư mục /DW/control/config_error
        // báo lỗi ko tìm thấy config.xml
        if (!configFile.exists()) {
            File errorFile = new File(errorFilePath + currentDate + "_source-crawl" + "config-error.txt");
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
            URL = getRequiredProperty(props, "url", "URL");
            USER_AGENT = getRequiredProperty(props, "user.agent", "USER_AGENT");
            TIMEOUT_MS = Integer.parseInt(getRequiredProperty(props, "timeout.ms", "TIMEOUT_MS"));
            outputPath = getRequiredProperty(props, "output.path", "OUTPUT_PATH");
            DB_URL = getRequiredProperty(props, "db_control", "DB_URL");
            USER = getRequiredProperty(props, "db_user_root_name", "USER");
            PASSWORD = getRequiredProperty(props, "db_user_root_pass", "PASSWORD");
        }

        //Xuất file ddMMyyyy_source-crawl_loadConfigValue-error.txt
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
        Giavang scraper = new Giavang();
        // 1. Load file config.xml
        for (String arg : args) { // Input: config_file_path
            if (arg.startsWith("config_file_path=")) {
                scraper.config_file_path = arg.substring("config_file_path=".length()).trim();
                System.out.println("Using config file: " + scraper.config_file_path);
            }
        }
        scraper.loadConfig();

        // 3. Kết nối tới database
        Connection conn = scraper.connectToDatabase();

        // Xuất file ddMMyyyy_source-crawl_connectDB-error.txt
        //trong thư mục /DW/control/config_error
        //báo lỗi báo lỗi ko thể kết nối đến DB
        if (conn == null) {
            File errorConnectDB = new File(errorFilePath + currentDate + "_source-crawl_connectDB-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(errorConnectDB))) {
                writer.write("Không thể kết nối tới database: " + scraper.DB_URL);
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không thể kết nối DB: " + scraper.DB_URL);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
        LocalDate localDate = LocalDate.parse(currentDate, formatter);
        String check_etl_log_status =
                "SELECT 1 process_code, status FROM etl_log " +
                        "WHERE process_code = 1 AND DATE(run_date) = ? " +
                        "ORDER BY log_id DESC LIMIT 1";
        PreparedStatement stmt = conn.prepareStatement(check_etl_log_status);
        stmt.setDate(1, java.sql.Date.valueOf(localDate));
        ResultSet rs = stmt.executeQuery();

        // 4. Kiểm tra trạng thái process 1 trong bảng etl_log trong DB control
        if (rs.next()) {
            String status = rs.getString("status");
            if (status.equals("PS")) {
                // Hiển thị lỗi "Ko thể chạy file do trạng thái hiện tại là: PS"
                throw new RuntimeException("Ko thể chạy file do trạng thái hiện tại là: " + status);
            }
        }

        try {
            // 5. Kết nối và đọc dữ liệu từ web
            Document doc = Jsoup.connect(scraper.URL)
                    .userAgent(scraper.USER_AGENT)
                    .timeout(scraper.TIMEOUT_MS)
                    .get();

            String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
            String filePath = scraper.outputPath + "giavang_" + currentDate + ".csv";

            // Xóa file giavang_ddmmyyyy.csv cũ
            File file = new File(filePath);
            if (file.exists()) {
                boolean deleted = file.delete();
                System.out.println("Deleted file " + filePath + ": " + deleted);
            }

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(filePath, true),
                            StandardCharsets.UTF_8
                    ))) {
                writer.write("Id, Khu vuc, Loai vang, Gia mua, Gia ban, url, timestamp\n");
                LocalDateTime now = LocalDateTime.now();
                String location = "";
                int id = 1;

                // 6. Extract dữ liệu
                Elements rows = doc.select("table tbody tr");

                // Ghi log vào DB control lỗi extract ko thành công
                if (rows.isEmpty()) {
                    String error_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
                    PreparedStatement s1 = conn.prepareStatement(error_sql);
                    s1.setInt(1, 1);
                    s1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                    s1.setString(3, "FL");
                    s1.setString(4, "Extract dữ liệu ko thành công");
                    s1.executeUpdate();

                    String file_log_sql = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
                    PreparedStatement s2 = conn.prepareStatement(file_log_sql);
                    s2.setInt(1, 1);
                    s2.setString(2, filePath);
                    s2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                    s2.setString(4, "FL");
                    s2.executeUpdate();

                    String detail_error_sql = "INSERT INTO error_log (process_code, error_time, error_message) VALUES (?,?,?);";
                    PreparedStatement s3 = conn.prepareStatement(detail_error_sql);
                    s3.setInt(1, 1);
                    s3.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                    s3.setString(3, "Extract dữ liệu ko thành công");
                    s3.executeUpdate();
                    throw new RuntimeException("Extract dữ liệu ko thành công, đã ghi log lỗi vào DB");
                }

                // 7. Lưu dữ liệu vào file giavang_ddmmyyyy.csv
                // và
                //Ghi log vào DB control việc ghi file thành công
                for (Element row : rows) {
                    Elements cols = row.select("td");
                    Elements heads = row.select("th");
                    if (cols.size() >= 3) {
                        Element aTag_brand = cols.get(0).selectFirst("a");
                        Elements aTag_location = heads.select("a");
                        if (!aTag_location.text().trim().isEmpty()) {
                            location = aTag_location.text().trim();
                        }
                        String url = aTag_brand != null ? aTag_brand.attr("href") : "";
                        String brand = aTag_brand != null ? aTag_brand.text() : "";
                        String mua = cols.get(1).text().trim();
                        String ban = cols.get(2).text().trim();
                        writer.write(String.format("%s,%s,%s,%s,%s,%s,%s\n",
                                id++, location, brand, mua, ban, url, now));
                    }
                }
                String success_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
                PreparedStatement s1 = conn.prepareStatement(success_sql);
                s1.setInt(1, 1);
                s1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                s1.setString(3, "SC");
                s1.setString(4, "Extract dữ liệu thành công");
                s1.executeUpdate();

                String file_log_sql = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
                PreparedStatement s2 = conn.prepareStatement(file_log_sql);
                s2.setInt(1, 1);
                s2.setString(2, filePath);
                s2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                s2.setString(4, "RD");
                s2.executeUpdate();
            }
            System.out.println("Done scraping at: " + LocalDateTime.now());

            // Ghi log vào DB control lỗi kết nối tới web ko thành công
        } catch (IOException e) {
            String error_sql = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement s1 = conn.prepareStatement(error_sql);
            s1.setInt(1, 1);
            s1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            s1.setString(3, "FL");
            s1.setString(4, "Ko thể kết nối tới trang web " + scraper.URL);
            s1.executeUpdate();

            String detail_error_sql = "INSERT INTO error_log (process_code, error_time, error_message) VALUES (?,?,?);";
            PreparedStatement s2 = conn.prepareStatement(detail_error_sql);
            s2.setInt(1, 1);
            s2.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            s2.setString(3, "Ko thể kết nối tới trang web " + scraper.URL);
            s2.executeUpdate();
            e.printStackTrace();
        }


        // tạm dừng chương trình một khoảng thời gian ngẫu nhiên 1–2 giây.
        try {
            Thread.sleep(1000 + ThreadLocalRandom.current().nextInt(500, 1500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}