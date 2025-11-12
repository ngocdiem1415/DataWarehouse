package com.example.dwcrawl;

import com.opencsv.CSVReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

public class DetailCrawl {
    private static final String INPUT_CSV = "D:\\DataWarehouse\\DW-Crawl\\data\\crawl12112025.csv";
    private static final String OUTPUT_DIR = "D:/DataWarehouse/DW-Crawl/data/";
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; MyJavaScraper/1.0; +https://example.com)";
    private static final int TIMEOUT_MS = 60_000;

    public static void main(String[] args) {
        String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        String outputFile = OUTPUT_DIR + "detail_crawl_" + currentDate + ".csv";

        try (CSVReader csvReader = new CSVReader(
                new InputStreamReader(new FileInputStream(INPUT_CSV), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(outputFile, true), StandardCharsets.UTF_8))) {

            writer.write("\"Khu vuc\",\"Loai vang\",\"Gia mua\",\"Gia ban\",\"timestamp\",\"Trang thai\"\n");

            String[] row;
            boolean isHeader = true;
            while ((row = csvReader.readNext()) != null) {
                if (isHeader) { // Bỏ dòng tiêu đề
                    isHeader = false;
                    continue;
                }

                // Đọc các cột từ CSV
                if (row.length < 5) continue; // bảo vệ nếu file thiếu cột

                String location = row[0].trim();
                String loaiVang = row[1].trim();
                String url = row[4].trim();

                if (url.isEmpty()) continue;

                try {
                    System.out.println("Crawling: " + url);
                    Document doc = Jsoup.connect(url)
                            .userAgent(USER_AGENT)
                            .timeout(TIMEOUT_MS)
                            .get();

                    LocalDateTime now = LocalDateTime.now();

                    Elements rows = doc.select("table tbody tr");

                    for (Element r : rows) {
                        Elements cols = r.select("td");
                        if (cols.size() >= 3) {
                            String brand = cols.get(0).text().trim();
                            String mua = cols.get(1).text().trim();
                            String ban = cols.get(2).text().trim();

                            writer.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                                    location, brand, mua, ban , now, "OK"));
                        }
                    }

                    // Nghỉ ngẫu nhiên để tránh bị chặn
                    Thread.sleep(1000 + ThreadLocalRandom.current().nextInt(500, 1500));

                } catch (IOException e) {
                    System.err.println("Lỗi khi crawl URL: " + url);
                    writer.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                            location, loaiVang, "", "", url, LocalDateTime.now(), "ERROR"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            System.out.println("Hoàn tất crawl chi tiết, lưu tại: " + outputFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
