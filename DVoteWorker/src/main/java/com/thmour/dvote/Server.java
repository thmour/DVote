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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author theofilos
 */

public class Server {
    static final Logger logger = Logger.getLogger(Server.class.getName());
    
    private final HttpServer server;
    private final Executor default_executor;
    private final ReadWriteLock[] fileLocks;
    private final int row_len = 2 * Short.BYTES + Integer.BYTES;
    private final HttpHandler store_data;
    private final HttpHandler results;
    private final HttpHandler alive;
    private final ConcurrentHashMap<Integer, Short>[] voteMap;
    private final String data_path;
    private final BlockingQueue<ByteBuffer> writeQueue;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();


    public Server(String path, String[] worker_addr, int port) throws IOException, URISyntaxException {
        this.writeQueue = new ArrayBlockingQueue(65536, true);
        this.data_path = path + "/data.bin";
        this.voteMap = new ConcurrentHashMap[worker_addr.length];
        this.fileLocks = new ReentrantReadWriteLock[worker_addr.length];
        for(int i = 0; i < worker_addr.length; i++) {
            this.voteMap[i] = new ConcurrentHashMap<>();
            this.fileLocks[i] = new ReentrantReadWriteLock();
        }
        
        int threads = Runtime.getRuntime().availableProcessors();
        default_executor = Executors.newFixedThreadPool(threads);

        this.store_data = (HttpExchange ht) -> {
            byte[] buffer = new byte[row_len];

            InputStream input = ht.getRequestBody();
            input.read(buffer);

            ByteBuffer bf = ByteBuffer.wrap(buffer);
            short worker = bf.getShort();
            int voter = bf.getInt();
            short vote = bf.getShort();
            
            int responseCode = 200;
            if (voteMap[worker].containsKey(voter)) {
                responseCode = 400;
            } else {
                try {
                    writeQueue.put(bf);
                    voteMap[worker].put(voter, vote);
                } catch (Exception ex) {
                    logger.log(Level.SEVERE, null, ex);
                    responseCode = 507;
                }
            }

            String message;
            switch (responseCode) {
                case 200:
                    message = "OK";
                    break;
                case 507:
                    message = "I/O Error";
                    break;
                default:
                    message = "Already exists";
                    break;
            }
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(responseCode, message.length());
                res.write(message.getBytes());
            }
        };
        
        this.results = (HttpExchange ht) -> {
            DataInputStream dis = new DataInputStream(ht.getRequestBody());
            int[] result_arr = new int[6];
            Arrays.fill(result_arr, 0);
            voteMap[dis.readInt()].forEach((key, value) -> {
                result_arr[value]++;
            });
            String message = Arrays.toString(result_arr);
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(200, message.length());
                res.write(message.getBytes());
            }
        };
        
        this.alive = (HttpExchange ht) -> {
            String message = "OK";
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(200, message.length());
                res.write(message.getBytes());
            }
        };
        
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/store", store_data);
        server.createContext("/results", results);
        server.createContext("/alive", alive);
        server.setExecutor(default_executor);
    }
    
    public int POST(String url_str, byte[] message) {
        try {
            URL url = new URL(url_str);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setDoOutput(true);
            con.setUseCaches(false);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Length", String.valueOf(message.length));
            con.setRequestProperty("Content-Type", "default/binary");
            try(OutputStream out = con.getOutputStream()) {
                out.write(message);
                out.flush();
            }
            con.connect();
            int tmp = con.getResponseCode();
            con.disconnect();
            return tmp;
        } catch (Exception ex) {
            logger.log(Level.SEVERE, null, ex);
        }
        return 500;
    }

    public boolean loadData() {
        File file = new File(data_path);
        if(!file.exists()) return false;
        
        int read, bufsize = 256*row_len;
        byte[] file_buffer = new byte[bufsize];
        try(FileInputStream fin = new FileInputStream(data_path)) {
            while((read = fin.read(file_buffer)) != -1) {
                ByteBuffer data = ByteBuffer.wrap(file_buffer);
                for(int i = read; i > 0; i -= row_len) {
                    voteMap[data.getShort()].put(data.getInt(), data.getShort());
                }
            }
            logger.log(Level.INFO, "Previous data loaded");
        } catch (Exception ex) {
            logger.log(Level.SEVERE, null, ex);
            System.exit(1);
        }
        
        return true;
    }
    
    public void start() {
        loadData();
        writer.execute(() -> {
            try(FileOutputStream fos = new FileOutputStream(data_path)) {
                while(true) {
                    ByteBuffer bf = writeQueue.take();
                    fos.write(bf.array());
                }
            } catch (Exception ex) {
                logger.log(Level.SEVERE, null, ex);
                System.exit(1);
            }
        });
        server.start();
    }

    public void stop() {
        writer.shutdownNow();
        server.stop(0);
    }
}
