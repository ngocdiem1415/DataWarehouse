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

public class DetailCrawl {
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

    private static String getRequiredProperty(Properties props, String key, String name) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new RuntimeException("Giá trị config " + name + " không được null hoặc rỗng");
        }
        return value;
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        value = value.replace("\"", "\"\"");
        return "\"" + value + "\""; // bao quanh bởi "
    }

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

    public void loadConfig() {
//        File configFile = new File("D:/DataWarehouse/DW-Crawl/config.xml");
        File configFile = new File(config_file_path);

        // Xuất file ddMMyyyy_source-crawl_config-error.txt
        // trong thư mục /DW/control/config_error
        // báo lỗi ko tìm thấy config.xml
        if (!configFile.exists()) {
            File errorFile = new File(errorFilePath + currentDate + "_detail-crawl" + "config-error.txt");
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
        DetailCrawl detailCrawl = new DetailCrawl();
        // 1. Load file config.xml
        for (String arg : args) { // Input: config_file_path
            if (arg.startsWith("config_file_path=")) {
                detailCrawl.config_file_path = arg.substring("config_file_path=".length()).trim();
                System.out.println("Using config file: " + detailCrawl.config_file_path);
            }
        }
        detailCrawl.loadConfig();

        // 3. Kết nối tới database
        Connection conn = detailCrawl.connectToDatabase();

        // Xuất file ddMMyyyy_source-crawl_connectDB-error.txt
        //trong thư mục /DW/control/config_error
        //báo lỗi báo lỗi ko thể kết nối đến DB
        if (conn == null) {
            File errorConnectDB = new File(errorFilePath + currentDate + "_source-crawl_connectDB-error.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(errorConnectDB))) {
                writer.write("Không thể kết nối tới database: " + detailCrawl.DB_URL);
            } catch (IOException ioEx) {
                ioEx.printStackTrace();
            }
            throw new RuntimeException("Không thể kết nối DB: " + detailCrawl.DB_URL);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
        LocalDate localDate = LocalDate.parse(currentDate, formatter);
        String check_etl_log_status1 =
                "SELECT 1 process_code, status FROM etl_log " +
                        "WHERE process_code = 1 AND DATE(run_date) = ? " +
                        "ORDER BY log_id DESC LIMIT 1";
        String check_etl_log_status2 =
                "SELECT 1 process_code, status FROM etl_log " +
                        "WHERE process_code = 2 AND DATE(run_date) = ? " +
                        "ORDER BY log_id DESC LIMIT 1";
        PreparedStatement stmt1 = conn.prepareStatement(check_etl_log_status1);
        PreparedStatement stmt2 = conn.prepareStatement(check_etl_log_status2);
        stmt1.setDate(1, java.sql.Date.valueOf(localDate));
        stmt2.setDate(1, java.sql.Date.valueOf(localDate));
        ResultSet rs1 = stmt1.executeQuery();
        ResultSet rs2 = stmt2.executeQuery();

        // 4. Kiểm tra trạng thái process trong bảng etl_log trong DB control
        if (rs1.next() && rs2.next()) {
            String process1_status = rs1.getString("status");
            String process2_status = rs2.getString("status");
            if (!process1_status.equals("SC")) {
                // Hiển thị lỗi "Ko thể chạy file do ..."
                throw new RuntimeException("Ko thể chạy file do trạng thái tiến trình 1 hiện tại là: " + process1_status);
            } else if (process2_status.equals("PS")) {
                // Hiển thị lỗi "Ko thể chạy file do ..."
                throw new RuntimeException("Ko thể chạy do tiến trình 2 đang có trạng thái: " + process2_status);
            }
        }

        String csvFile = detailCrawl.outputPath + "giavang_" + currentDate + ".csv";
        String outputFile = detailCrawl.outputPath + "detail_crawl_" + currentDate + ".csv";

        // Ghi log vào DB control lỗi file giavang_ddmmYYYY.csv ko tồn tại
        File file = new File(csvFile);
        if (!file.exists()) {
            String input_file_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement input_file_stmt1 = conn.prepareStatement(input_file_sql1);
            input_file_stmt1.setInt(1, 2);
            input_file_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            input_file_stmt1.setString(3, "FL");
            input_file_stmt1.setString(4, "Tiến trình 2 ko thể thực thi");
            input_file_stmt1.executeUpdate();

            String input_file_sql2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
            PreparedStatement input_file_stmt2 = conn.prepareStatement(input_file_sql2);
            input_file_stmt2.setInt(1, 2);
            input_file_stmt2.setString(2, csvFile);
            input_file_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            input_file_stmt2.setString(4, "FL");
            input_file_stmt2.executeUpdate();

            String input_file_sql3 = "INSERT INTO error_log (process_code, error_time, error_message) VALUES (?,?,?);";
            PreparedStatement input_file_stmt3 = conn.prepareStatement(input_file_sql3);
            input_file_stmt3.setInt(1, 2);
            input_file_stmt3.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            input_file_stmt3.setString(3, "File csv ko tồn tại: " + csvFile);
            input_file_stmt3.executeUpdate();
            throw new RuntimeException("File csv ko tồn tại, đã ghi log lỗi vào DB");
        }

        // Xóa file detail_crawl_ddmmyyyy.csv cũ
        File file_output = new File(outputFile);
        if (file_output.exists()) {
            boolean deleted = file_output.delete();
            System.out.println("Deleted file " + outputFile + ": " + deleted);
        }

        // 5. Ghi log tiến trình đang thực hiện vào DB control
        //và
        //Đọc dữ liệu từ file giavang_ddmmYYYY.csv
        String process_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
        PreparedStatement process_status_stmt1 = conn.prepareStatement(process_sql1);
        process_status_stmt1.setInt(1, 2);
        process_status_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
        process_status_stmt1.setString(3, "PS");
        process_status_stmt1.setString(4, "Đang tiến hành tiến trình 2");
        process_status_stmt1.executeUpdate();
        String process_sql2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
        PreparedStatement process_status_stmt2 = conn.prepareStatement(process_sql2);
        process_status_stmt2.setInt(1, 2);
        process_status_stmt2.setString(2, csvFile);
        process_status_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
        process_status_stmt2.setString(4, "RD");
        process_status_stmt2.executeUpdate();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(outputFile, true), StandardCharsets.UTF_8))) {
            writer.write("Source_id,Source_url,Brand,Location,Gold_type,Buy_price,Sell_price,Unit,crawl_time\n");
            String line;
            boolean isHeader = true;
            int count = 1;

            // 6. Ghi log cho tiến trình với trạng thái "RN" vào bảng file_config trong DB control
            //và
            //Kết nối và đọc dữ liệu từ web theo url trong giavang_ddmmYYYY.csv
            String running_status_sql = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
            PreparedStatement running_stmt = conn.prepareStatement(running_status_sql);
            running_stmt.setInt(1, 2);
            running_stmt.setString(2, outputFile);
            running_stmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            running_stmt.setString(4, "RN");
            running_stmt.executeUpdate();
            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                String[] cols = line.split(",", -1);
                if (cols.length < 5) continue;
                String source_id = cols[0].trim();
                String location = cols[1].trim();
                String gold_type = cols[2].trim();
                String url = cols[5].trim();
                if (url.isEmpty()) continue;
                try {
                    System.out.println(count + "/ " + "Crawling: " + url);
                    Document doc = Jsoup.connect(url)
                            .userAgent(detailCrawl.USER_AGENT)
                            .timeout(detailCrawl.TIMEOUT_MS)
                            .get();

                    LocalDateTime crawlTime = LocalDateTime.now();
                    DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                    String unit = "x1000đ/lượng";

                    // 7. Extract dữ liệu
                    Elements rows = doc.select("table tbody tr");

                    // Ghi log vào DB control lỗi extract ko thành công
                    if (rows.isEmpty()) {
                        String failed_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
                        PreparedStatement failed_status_stmt1 = conn.prepareStatement(failed_sql1);
                        failed_status_stmt1.setInt(1, 2);
                        failed_status_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                        failed_status_stmt1.setString(3, "FL");
                        failed_status_stmt1.setString(4, "Tiến trình 2 bị hủy");
                        failed_status_stmt1.executeUpdate();

                        String failed_sql2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
                        PreparedStatement failed_status_stmt2 = conn.prepareStatement(failed_sql2);
                        failed_status_stmt2.setInt(1, 2);
                        failed_status_stmt2.setString(2, outputFile);
                        failed_status_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                        failed_status_stmt2.setString(4, "FL");
                        failed_status_stmt2.executeUpdate();

                        String failed_sql3 = "INSERT INTO error_log (process_code, error_time, error_message, error_file) VALUES (?,?,?,?);";
                        PreparedStatement failed_status_stmt3 = conn.prepareStatement(failed_sql3);
                        failed_status_stmt3.setInt(1, 2);
                        failed_status_stmt3.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                        failed_status_stmt3.setString(3, "Extract dữ liệu ko thành công: " + url);
                        failed_status_stmt3.setString(4, outputFile);
                        failed_status_stmt3.executeUpdate();
                    }

                    //8. Lưu dữ liệu vào file detail_crawl.csv
                    //và
                    //Ghi log cho tiến trình sau khi chạy xong trong DB control
                    for (Element r : rows) {
                        Elements cells = r.select("th, td");
                        if (cells.size() < 3) continue;
                        String brand = "";
                        String buy_price = "";
                        String sell_price = "";
                        if (cells.size() == 3) {
                            brand = cells.get(0).text().trim();
                            buy_price = cells.get(1).text().trim();
                            sell_price = cells.get(2).text().trim();
                        } else {
                            brand = cells.get(1).text().trim();
                            buy_price = cells.get(2).text().trim();
                            sell_price = cells.get(3).text().trim();
                            if ("Bảo Tín Minh Châu".equals(gold_type)) {
                                brand = cells.get(0).text().trim() + "-" + brand;
                            }
                        }
                        writer.write(String.join(",",
                                escapeCsv(source_id),
                                escapeCsv(url),
                                escapeCsv(gold_type),
                                escapeCsv(location),
                                escapeCsv(brand),
                                escapeCsv(buy_price),
                                escapeCsv(sell_price),
                                escapeCsv(unit),
                                escapeCsv(crawlTime.toString())) + "\n");
                    }
                    count++;
                    Thread.sleep(1000 + ThreadLocalRandom.current().nextInt(500, 1500));
                }

                // Ghi log vào DB control lỗi kết nối tới web ko thành công
                catch (IOException e) {
                    System.err.println("Lỗi khi crawl URL: " + url + " -> " + e.getMessage());
                    String error_web_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
                    PreparedStatement error_web_stmt1 = conn.prepareStatement(error_web_sql1);
                    error_web_stmt1.setInt(1, 2);
                    error_web_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                    error_web_stmt1.setString(3, "FL");
                    error_web_stmt1.setString(4, "Tiến trình 2 bị hủy");
                    error_web_stmt1.executeUpdate();

                    String error_web_sql2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
                    PreparedStatement error_web_stmt2 = conn.prepareStatement(error_web_sql2);
                    error_web_stmt2.setInt(1, 2);
                    error_web_stmt2.setString(2, outputFile);
                    error_web_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                    error_web_stmt2.setString(4, "FL");
                    error_web_stmt2.executeUpdate();

                    String error_web_sql3 = "INSERT INTO error_log (process_code, error_time, error_message, error_file) VALUES (?,?,?);";
                    PreparedStatement error_web_stmt3 = conn.prepareStatement(error_web_sql3);
                    error_web_stmt3.setInt(1, 2);
                    error_web_stmt3.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                    error_web_stmt3.setString(3, "Ko thể kết nối tới trang web " + url);
                    error_web_stmt3.executeUpdate();
                    e.printStackTrace();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("Hoàn tất crawl chi tiết, lưu tại: " + outputFile);

            //8. Lưu dữ liệu vào file detail_crawl.csv
            //và
            //Ghi log cho tiến trình sau khi chạy xong trong DB control
            String success_sql1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement success_status_stmt1 = conn.prepareStatement(success_sql1);
            success_status_stmt1.setInt(1, 2);
            success_status_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            success_status_stmt1.setString(3, "SC");
            success_status_stmt1.setString(4, "Hoàn thành tiến trình 2");
            success_status_stmt1.executeUpdate();
            String success_sql2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
            PreparedStatement success_status_stmt2 = conn.prepareStatement(success_sql2);
            success_status_stmt2.setInt(1, 2);
            success_status_stmt2.setString(2, outputFile);
            success_status_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            success_status_stmt2.setString(4, "SC");
            success_status_stmt2.executeUpdate();
        }

        // Ghi log vào DB control đọc dữ liệu từ file giavang_ddmmYYYY.csv ko thành công
        catch (Exception e) {
            String input_file_error1 = "INSERT INTO etl_log (process_code, run_date, status, log_message) VALUES (?,?,?,?);";
            PreparedStatement input_file_error_stmt1 = conn.prepareStatement(input_file_error1);
            input_file_error_stmt1.setInt(1, 2);
            input_file_error_stmt1.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            input_file_error_stmt1.setString(3, "FL");
            input_file_error_stmt1.setString(4, "Đọc dữ liệu từ file " + csvFile + " ko thành công");
            input_file_error_stmt1.executeUpdate();

            String input_file_error2 = "INSERT INTO file_config (process_code, file_source, create_at_file, status) VALUES (?,?,?,?);";
            PreparedStatement input_file_error_stmt2 = conn.prepareStatement(input_file_error2);
            input_file_error_stmt2.setInt(1, 2);
            input_file_error_stmt2.setString(2, outputFile);
            input_file_error_stmt2.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            input_file_error_stmt2.setString(4, "FL");
            input_file_error_stmt2.executeUpdate();

            String detail_error_sql = "INSERT INTO error_log (process_code, error_time, error_message, error_file) VALUES (?,?,?,?);";
            PreparedStatement s3 = conn.prepareStatement(detail_error_sql);
            s3.setInt(1, 2);
            s3.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            s3.setString(3, "Đọc dữ liệu từ file " + csvFile + " ko thành công");
            s3.setString(4, csvFile);
            s3.executeUpdate();
            e.printStackTrace();
        }
    }
}