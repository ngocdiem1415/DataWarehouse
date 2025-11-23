package com.example.dwcrawl.Staging;

import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class Transform {
    private String DB_URL;
    private String USER;
    private String PASSWORD;
    private static final String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    private static final String errorFilePath = "D:/DataWarehouse/DW-Crawl/data/";

    public void loadConfig() {
        File configFile = new File("D:/DataWarehouse/DW-Crawl/config.xml");
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

            DB_URL = props.getProperty("db_staging");
            USER = props.getProperty("db_user_root_name");
            PASSWORD = props.getProperty("db_user_root_pass");
        } catch (Exception e) {
            throw new RuntimeException("Lỗi load config.xml: " + e.getMessage(), e);
        }
    }

    public String formatString(String s) {
        s = StringUtils.stripAccents(s);
        s = s.replace("Đ", "D").replace("đ", "d");
        s = s.replaceAll("[^a-zA-Z0-9]", "");
        return s;
    }

    public static void main(String[] args) {
        Transform clean = new Transform();
        clean.loadConfig();
        try (Connection conn = DriverManager.getConnection(clean.DB_URL, clean.USER, clean.PASSWORD)) {

            String truncate_sql = "TRUNCATE TABLE stg_gold_price_clean";
            try (PreparedStatement truncate_stmt = conn.prepareStatement(truncate_sql)) {
                truncate_stmt.executeUpdate();
                System.out.println("Bảng stg_gold_price_clean đã được truncate");
            }

            String select_all = "select DISTINCT d.brand, d.location, d.gold_type, d.buy_price, d.sell_price, d.unit, s.brand_url, d.`timestamp` " +
                    "from stg_gold_price_detail as d\n" +
                    "join stg_gold_price_source as s\n" +
                    "on d.source_id = s.id";
            PreparedStatement select_stmt = conn.prepareStatement(select_all);
            ResultSet rs = select_stmt.executeQuery();
            String insert_sql = "INSERT INTO stg_gold_price_clean " +
                    "(record_id, brand, location, gold_type, buy_price, sell_price, unit, source, `timestamp`, load_date) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement insert_stmt = conn.prepareStatement(insert_sql);

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


                if (gold_type == null || gold_type.trim().isEmpty()) {
                    continue;
                }

                insert_stmt.setString(1, id1 + "-" + id2 + "-" + id3);
                insert_stmt.setString(2, brand);
                insert_stmt.setString(3, location);
                insert_stmt.setString(4, gold_type);

                String buyPriceStr = rs.getString("buy_price");
                double buyPrice = (!buyPriceStr.equals("-")) ? Double.parseDouble(buyPriceStr) : -1;
                String sell_priceStr = rs.getString("sell_price");
                double sell_price = (!sell_priceStr.equals("-")) ? Double.parseDouble(sell_priceStr) : -1;

                insert_stmt.setDouble(5, buyPrice);
                insert_stmt.setDouble(6, sell_price);
                insert_stmt.setString(7, unit);
                insert_stmt.setString(8, brand_url);
                insert_stmt.setString(9, timestamp);
                java.sql.Timestamp loadDateTime = java.sql.Timestamp.valueOf(LocalDate.now().atStartOfDay());
                insert_stmt.setTimestamp(10, loadDateTime);

                insert_stmt.executeUpdate();
            }
            System.out.println("Insert dữ liệu hoàn tất");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
