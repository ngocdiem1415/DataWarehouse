package com.example.dwcrawl.Staging;

import com.opencsv.CSVReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LoadCrawl {
    private String DB_URL;
    private String USER;
    private String PASSWORD;
    private String outputPath;
    private static final String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));

    public void loadConfig() {
        File configFile = new File("D:/DataWarehouse/DW-Crawl/config.xml");
        if (!configFile.exists()) {
            throw new RuntimeException("Không tìm thấy file config.xml");
        }
        try (InputStream input = new FileInputStream(configFile)) {
            java.util.Properties props = new java.util.Properties();
            props.loadFromXML(input);

            DB_URL = props.getProperty("db_staging");
            USER = props.getProperty("db_user_root_name");
            PASSWORD = props.getProperty("db_user_root_pass");
            outputPath = props.getProperty("output.path");
        } catch (Exception e) {
            throw new RuntimeException("Lỗi load config.xml: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        LoadCrawl loadData = new LoadCrawl();
        loadData.loadConfig();

        String csvFile = loadData.outputPath + currentDate + ".csv";

        try (
                Connection conn = DriverManager.getConnection(loadData.DB_URL, loadData.USER, loadData.PASSWORD);
                CSVReader reader = new CSVReader(new FileReader(csvFile))) {

            String truncate = "TRUNCATE TABLE stg_gold_price_source";
            PreparedStatement trunc_stmt = conn.prepareStatement(truncate);

            // Disable FK
            conn.prepareStatement("SET FOREIGN_KEY_CHECKS = 0").execute();

            // Truncate stg_gold_price_detail
            PreparedStatement truncDetail = conn.prepareStatement("TRUNCATE TABLE stg_gold_price_detail");
            truncDetail.executeUpdate();

            // Truncate stg_gold_price_source
            trunc_stmt.executeUpdate();

            System.out.println("Đã truncate bảng source và detail");

            // Enable FK
            conn.prepareStatement("SET FOREIGN_KEY_CHECKS = 1").execute();

            String insertSQL = "INSERT INTO stg_gold_price_source (location, brand, buy_price, sell_price, brand_url, crawl_time, status) VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement stmt = conn.prepareStatement(insertSQL);

            String[] nextLine;
            reader.readNext();

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            while ((nextLine = reader.readNext()) != null) {
                String location = nextLine[1].trim();
                String brand = nextLine[2].trim();
                double giaMua = Double.parseDouble(nextLine[3].trim());
                double giaBan = Double.parseDouble(nextLine[4].trim());
                String url = nextLine[5].trim();
                String ts = nextLine[6].trim();

                stmt.setString(1, location);
                stmt.setString(2, brand);
                stmt.setString(3, String.valueOf(giaMua));
                stmt.setString(4, String.valueOf(giaBan));
                stmt.setString(5, url);
                stmt.setString(6, ts);
                stmt.setString(7, "OK");

                stmt.addBatch();
            }

            int[] result = stmt.executeBatch();
            System.out.println("Inserted " + result.length + " into crawl_data");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}