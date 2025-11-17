package com.example.dwcrawl;

import com.opencsv.CSVReader;

import java.io.File;
import java.io.FileReader;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LoadData {
    private static final String JDBC_URL = "jdbc:mysql://157.245.199.151:3306/staging";
    private static final String USER = "root";
    private static final String PASSWORD = "uBuntu@123a";

    public static void main(String[] args) {
//        String csvFile = "D:\\DataWarehouse\\crawl03112025.csv";
        String csvFile = "/home/DW/staging/data/crawl03112025.csv";
//        LocalDate today = LocalDate.now();
//        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("ddMMyyyy");
//        String dateStr = today.format(dateFormatter);
//        String fileName = "crawl" + dateStr + ".csv";
//        String folderPath = "/home/ubuntu/data/";
//        String csvFile = folderPath + fileName;
//        File file = new File(csvFile);
//
//        if (!file.exists()) {
//            System.err.println("Không tìm thấy file: " + csvFile);
//            return;
//        }
//        System.out.println("Đang đọc file: " + csvFile);

        try (
                Connection conn = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
                CSVReader reader = new CSVReader(new FileReader(csvFile))
        ) {
            String insertSQL = "INSERT INTO crawl_data (khu_vuc, brand, gia_mua, gia_ban, url, timestamp, trang_thai) VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement stmt = conn.prepareStatement(insertSQL);

            String[] nextLine;
            reader.readNext();

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            while ((nextLine = reader.readNext()) != null) {
                String khuVuc = nextLine[0];
                String brand = nextLine[1];
                double giaMua = Double.parseDouble(nextLine[2]);
                double giaBan = Double.parseDouble(nextLine[3]);
                String url = nextLine[4];
                String timestampStr = nextLine[5];
                String trangThai = nextLine[6];

                Timestamp ts = null;
                try {
                    LocalDateTime dateTime = LocalDateTime.parse(timestampStr);
                    ts = Timestamp.valueOf(dateTime);
                } catch (Exception e) {
                    System.out.println("Lỗi parse timestamp: " + timestampStr);
                }

                stmt.setString(1, khuVuc);
                stmt.setString(2, brand);
                stmt.setString(3, String.valueOf(giaMua));
                stmt.setString(4, String.valueOf(giaBan));
                stmt.setString(5, url);
                stmt.setString(6, String.valueOf(ts));
                stmt.setString(7, trangThai);

                stmt.addBatch();
            }

            int[] result = stmt.executeBatch();
            System.out.println("Inserted " + result.length + " into crawl_data");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
