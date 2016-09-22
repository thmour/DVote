/*
 * Copyright (C) 2016 theofilos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.thmour.dvote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 *
 * @author theofilos
 */
public class Main {

    public static int POST(String url_str, byte[] message) {
        try {
            URL url = new URL(url_str);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setDoOutput(true);
            con.setUseCaches(false);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Length", String.valueOf(message.length));
            con.setRequestProperty("Content-Type", "default/binary");
            try (OutputStream out = con.getOutputStream()) {
                out.write(message);
                out.flush();
            }

            return con.getResponseCode();
        } catch (Exception ex) {
        }
        return 500;
    }
    
    private static String RESULTS(String url_str) {
        try {
            URL url = new URL(url_str);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setUseCaches(false);
            con.setRequestMethod("POST");
            con.setConnectTimeout(700);
            if (con.getResponseCode() != 200) {
                return null;
            }
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(con.getInputStream()))) {
                String inputLine;
                StringBuilder response = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                return response.toString();
            }
        } catch (Exception ex) {
            //logger.log(Level.SEVERE, null, ex);
        }
        return null;
    }

    public static void main(String[] args) {
        Map<Integer, Integer> codeMap = new HashMap<>();
        Random r = new Random();
        if (args.length == 3) {
            String host = args[0];
            int requests = Integer.valueOf(args[1]);
            int candidates = Integer.valueOf(args[2]);
            for (int i = 0; i < requests; i++) {
                int code = POST("http://"+host+"/vote",
                        ("voter=" + i + "&candidate=" + r.nextInt(candidates)).getBytes());
                Integer count = codeMap.get(code);
                codeMap.put(code, count == null ? 1 : count + 1);
            }
            System.out.println(codeMap);
            System.out.println(RESULTS("http://"+host+"/results"));
        } else {
            System.err.println("Invalid arguments: <host:port> <#requests> <#candidates>");
        }
    }
}
