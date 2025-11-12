package com.example.dwcrawl;

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
    private static final String URL = "https://giavang.org/trong-nuoc/";
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; MyJavaScraper/1.0; +https://example.com)";
    private static final int TIMEOUT_MS = 60_000;

    public static void main(String[] args) {
        try {
            Document doc = Jsoup.connect(URL)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();
            String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
//            String filePath = "/home/DW/staging/data/giavang_" + currentDate + ".csv";
            String filePath = "D:/DataWarehouse/DW-Crawl/data/crawl" + currentDate + ".csv";

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(filePath, true),
                            StandardCharsets.UTF_8
                    ))) {
                writer.write("Khu vuc, Loai vang, Gia mua, Gia ban, url, timestamp, Trang thai\n");
                LocalDateTime now = LocalDateTime.now();
                String location = "";

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
                                location, brand, mua, ban, url, now, "status"));
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
