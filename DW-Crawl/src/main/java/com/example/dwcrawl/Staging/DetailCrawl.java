package com.example.dwcrawl.Staging;

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
    private String USER_AGENT;
    private int TIMEOUT_MS;
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

            USER_AGENT = props.getProperty("user.agent");
            TIMEOUT_MS = Integer.parseInt(props.getProperty("timeout.ms"));
            outputPath = props.getProperty("output.path");
        } catch (Exception e) {
            throw new RuntimeException("Lỗi load config.xml: " + e.getMessage(), e);
        }
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        value = value.replace("\"", "\"\""); // escape dấu "
        return "\"" + value + "\""; // bao quanh bởi "
    }

    public static void main(String[] args) {
        DetailCrawl detailCrawl = new DetailCrawl();
        detailCrawl.loadConfig();

        String csvFile = detailCrawl.outputPath + currentDate + ".csv";
        String outputFile = detailCrawl.outputPath + "detail_crawl_" + currentDate + ".csv";

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(outputFile, true), StandardCharsets.UTF_8))) {

            writer.write("Source_id,Source_url,Brand,Location,Gold_type,Buy_price,Sell_price,Unit,crawl_time\n");
            String line;
            boolean isHeader = true;
            int count = 1;
            while ((line = reader.readLine()) != null) {
                if (isHeader) { // bỏ qua header
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
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                    String unit = "x1000đ/lượng";
                    Elements rows = doc.select("table tbody tr");
                    for (Element r : rows) {
                        Elements td = r.select("td");
                        if (td.size() >= 3) {
                            String brand = td.get(0).text().trim();
                            String buy_price = td.get(1).text().trim();
                            String sell_price = td.get(2).text().trim();

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
                    }

                    count++;
                    Thread.sleep(1000 + ThreadLocalRandom.current().nextInt(500, 1500));
                } catch (IOException e) {
                    System.err.println("❌ Lỗi khi crawl URL: " + url + " -> " + e.getMessage());
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
