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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

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

    public static void main(String[] args) {
        for (int i = 0; i < 10000; i++) {
            POST("http://localhost:8000/vote", ("voter=" + i + "&candidate=" + (i % 6)).getBytes());
            if(i % 1000 == 0) System.out.println((i/100) + "%");
        }
    }
}
