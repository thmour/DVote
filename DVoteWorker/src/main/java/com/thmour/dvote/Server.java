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
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author theofilos
 */
class Server {

    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());

    private final HttpServer server;
    private final String data_path;
    private final int row_len = 2 * Short.BYTES + Integer.BYTES + Long.BYTES;
    private final ConcurrentHashMap<Integer, VoteEntry>[] voteMap;
    private final AtomicLongArray[] voteResults;
    private final BlockingQueue<ByteBuffer> writeQueue;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private class VoteEntry {
        final short candidate;
        final long timestamp;

        VoteEntry(short candidate, long timestamp) {
            this.candidate = candidate;
            this.timestamp = timestamp;
        }
    }

    Server(String path, String[] worker_addr, int numcandidates, int port)
            throws IOException, URISyntaxException {
        this.writeQueue = new ArrayBlockingQueue<>(1, true);
        this.data_path = path + "/data.bin";
        int num_workers = worker_addr.length;
        this.voteResults = new AtomicLongArray[num_workers];
        this.voteMap = new ConcurrentHashMap[num_workers];
        for (int i = 0; i < num_workers; i++) {
            this.voteMap[i] = new ConcurrentHashMap<>();
            this.voteResults[i] = new AtomicLongArray(numcandidates);
        }

        int threads = Runtime.getRuntime().availableProcessors();
        Executor default_executor = Executors.newFixedThreadPool(threads);

        HttpHandler store_data = (HttpExchange ht) -> {
            byte[] buffer = new byte[row_len];

            InputStream input = ht.getRequestBody();
            input.read(buffer);

            ByteBuffer bf = ByteBuffer.wrap(buffer);
            short worker = bf.getShort();
            int voter = bf.getInt();
            short vote = bf.getShort();
            long timestamp = bf.getLong();

            int responseCode = 200;
            if (voteMap[worker].containsKey(voter)) {
                responseCode = 400;
            } else {
                try {
                    writeQueue.put(bf);
                    voteMap[worker].put(voter, new Server.VoteEntry(vote, timestamp));
                    voteResults[worker].getAndIncrement(vote);
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, null, ex);
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

        HttpHandler results = (HttpExchange ht) -> {
            DataInputStream dis = new DataInputStream(ht.getRequestBody());
            short data_index = dis.readShort();
            StringBuilder sb = new StringBuilder();
            sb.append(voteResults[data_index].get(0));
            for (int i = 1; i < numcandidates; i++) {
                sb.append(",").append(voteResults[data_index].get(i));
            }
            String message = sb.toString();
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(200, message.length());
                res.write(message.getBytes());
            }
        };

        HttpHandler alive = (HttpExchange ht) -> {
            String message = "OK";
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(200, message.length());
                res.write(message.getBytes());
            }
        };

        HttpHandler resolve = (HttpExchange ht) -> {
            DataInputStream ds = new DataInputStream(ht.getRequestBody());
            short to_worker = ds.readShort();
            short data_id = ds.readShort();
            long start_time = ds.readLong();
            long end_time = ds.readLong();

            ArrayList<ByteBuffer> toBeSent = new ArrayList<>();

            voteMap[data_id].forEach((Integer voter, Server.VoteEntry vote_pair) -> {
                long vote_timestamp = vote_pair.timestamp;
                if (vote_timestamp > start_time && vote_timestamp < end_time) {
                    ByteBuffer bf = ByteBuffer.allocate(row_len)
                            .putShort(data_id)
                            .putInt(voter)
                            .putShort(vote_pair.candidate)
                            .putLong(vote_timestamp);
                    toBeSent.add(bf);
                }
            });

            int responseCode = 200;
            if (toBeSent.size() > 0) {
                byte[] message = new byte[toBeSent.size() * row_len];
                int row = 0;
                for (ByteBuffer bf : toBeSent) {
                    bf.get(message, row * row_len, row_len);
                    row++;
                }
                responseCode = POST("http://" + worker_addr[to_worker]
                        + ":" + port + "/batch_store", message);
            }
            String message = responseCode == 200 ? "OK" : "Batch load failed";
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(responseCode, message.length());
                res.write(message.getBytes());
            }
        };

        HttpHandler batch_store = (HttpExchange ht) -> {
            ByteBuffer bf;
            byte[] buffer = new byte[row_len];
            int voter, responseCode = 200;
            long timestamp;
            short worker, vote;

            InputStream input = ht.getRequestBody();
            while (input.read(buffer) != -1) {
                bf = ByteBuffer.wrap(buffer);
                worker = bf.getShort();
                voter = bf.getInt();
                vote = bf.getShort();
                timestamp = bf.getLong();
                try {
                    writeQueue.put(bf);
                    voteMap[worker].put(voter, new Server.VoteEntry(vote, timestamp));
                    voteResults[worker].getAndIncrement(vote);
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, null, ex);
                    responseCode = 507;
                }
            }
            String message = responseCode == 200 ? "OK" : "Batch load failed";
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(responseCode, message.length());
                res.write(message.getBytes());
            }
        };

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/store", store_data);
        server.createContext("/results", results);
        server.createContext("/alive", alive);
        server.createContext("/resolve", resolve);
        server.createContext("/batch_store", batch_store);
        server.setExecutor(default_executor);
        LOGGER.log(Level.INFO, "Server ready at {0}", String.valueOf(port));
    }

    private int POST(String url_str, byte[] message) {
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
            con.connect();
            int tmp = con.getResponseCode();
            con.disconnect();
            return tmp;
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, null, ex);
        }
        return 500;
    }

    private boolean loadData() {
        File file = new File(data_path);
        if (!file.exists()) {
            return false;
        }

        int rows = 0;
        int read, bufsize = 256 * row_len;
        byte[] file_buffer = new byte[bufsize];
        try (FileInputStream fin = new FileInputStream(data_path)) {
            while ((read = fin.read(file_buffer)) != -1) {
                ByteBuffer data = ByteBuffer.wrap(file_buffer);
                for (int i = read; i > 0; i -= row_len) {
                    rows++;
                    short data_id = data.getShort();
                    int voter = data.getInt();
                    short candidate = data.getShort();
                    long timestamp = data.getLong();
                    voteMap[data_id].put(voter,
                            new VoteEntry(candidate, timestamp));
                    voteResults[data_id].incrementAndGet(candidate);
                }
            }
            LOGGER.log(Level.INFO, "Previous data loaded: {0} rows", rows);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, null, ex);
            System.exit(1);
        }

        return true;
    }

    void start() {
        loadData();
        writer.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(data_path, true)) {
                while (true) {
                    ByteBuffer bf = writeQueue.take();
                    fos.write(bf.array());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, null, ex);
                System.exit(1);
            }
        });
        server.start();
    }

    void stop() {
        writer.shutdownNow();
        server.stop(0);
    }
}
