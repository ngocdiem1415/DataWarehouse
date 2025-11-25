package com.example.dwcrawl.Warehouse;

import org.apache.commons.lang3.StringUtils;

public class bla {
    public static void main(String[] args) {
        String original = "Đắk Lắk";
        String noAccent = StringUtils.stripAccents(original);
        System.out.println(noAccent);
    }
}
