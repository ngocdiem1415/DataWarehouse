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

public class GiavangScraper {
    private String URL;
    private String USER_AGENT;
    private int TIMEOUT_MS;
    private String outputPath;

    public void loadConfig() {
        File configFile = new File("D:/DataWarehouse/DW-Crawl/config.xml");
        if (!configFile.exists()) {
            throw new RuntimeException("Không tìm thấy file config.xml");
        }
        try (InputStream input = new FileInputStream(configFile)) {
            java.util.Properties props = new java.util.Properties();
            props.loadFromXML(input);

            URL = props.getProperty("url");
            USER_AGENT = props.getProperty("user.agent");
            TIMEOUT_MS = Integer.parseInt(props.getProperty("timeout.ms"));
            outputPath = props.getProperty("output.path");
        } catch (Exception e) {
            throw new RuntimeException("Lỗi load config.xml: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        GiavangScraper scraper = new GiavangScraper();
        scraper.loadConfig();

        try {
            Document doc = Jsoup.connect(scraper.URL)
                    .userAgent(scraper.USER_AGENT)
                    .timeout(scraper.TIMEOUT_MS)
                    .get();
            String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
            String filePath = scraper.outputPath + currentDate + ".csv";

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(filePath, true),
                            StandardCharsets.UTF_8
                    ))) {
                writer.write("Id, Khu vuc, Loai vang, Gia mua, Gia ban, url, timestamp\n");
                LocalDateTime now = LocalDateTime.now();
                String location = "";
                int id = 1;
                Elements rows = doc.select("table tbody tr");
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
            }
            System.out.println("Done scraping at: " + LocalDateTime.now());
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            Thread.sleep(1000 + ThreadLocalRandom.current().nextInt(500, 1500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
