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

public class LoadDetail {
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
        LoadDetail loadData = new LoadDetail();
        loadData.loadConfig();

        String csvFile = loadData.outputPath + "detail_crawl_" + currentDate + ".csv";

        try (
                Connection conn = DriverManager.getConnection(loadData.DB_URL, loadData.USER, loadData.PASSWORD);
                CSVReader reader = new CSVReader(new FileReader(csvFile))
        ) {
            String insertSQL = "INSERT INTO stg_gold_price_detail (source_id, brand, location, gold_type, buy_price, sell_price, unit, timestamp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement stmt = conn.prepareStatement(insertSQL);

            String[] nextLine;
            // Bỏ header
            reader.readNext();

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}