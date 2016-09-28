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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author theofilos
 */
class Server {

    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());

    private final Object TimestampLock = new Object();
    private final HttpServer server;
    private final int replicationFactor;
    private final int worker_port;
    private final String[] workers;
    private final boolean[] available;
    private final int msg_len = 2 * Short.BYTES + Integer.BYTES + Long.BYTES;
    private final int msg_resolve_len = 2 * Short.BYTES + 2 * Long.BYTES;
    private final long[] data_timestamps;
    private final ConcurrentLinkedQueue<DataMiss> data_miss_queue;
    private final ScheduledExecutorService ping_pong;
    private final ScheduledExecutorService inconsistency;
    private final int num_workers;

    private class DataMiss {

        final int worker;
        final long start;
        final long end;

        DataMiss(int worker, long start, long end) {
            this.worker = worker;
            this.start = start;
            this.end = end;
        }
    }

    private enum WorkerAction {
        STORE("/store"),
        RESULTS("/results"),
        ALIVE("/alive"),
        RESOLVE("/resolve");

        private final String value;

        WorkerAction(String value) {
            this.value = value;
        }
    }

    private String makeURL(int wid, WorkerAction uri) {
        return "http://" + workers[wid] + ":" + worker_port + uri.value;
    }

    Server(int replicationFactor, String[] workers, int candidates, int port,
           int worker_port) throws IOException {
        this.num_workers = workers.length;
        this.ping_pong = Executors.newSingleThreadScheduledExecutor();
        this.inconsistency = Executors.newSingleThreadScheduledExecutor();
        this.replicationFactor = replicationFactor;
        this.workers = workers;
        this.worker_port = worker_port;
        this.data_timestamps = new long[num_workers];
        this.available = new boolean[num_workers];
        Arrays.fill(available, true);
        Arrays.fill(data_timestamps, 0L);
        this.data_miss_queue = new ConcurrentLinkedQueue<>();

        HttpHandler mainHandler = (HttpExchange ht) -> {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(ht.getRequestBody()));
            String query = in.readLine();

            int voter = -1;
            short candidate = -1;
            for (String key_value : query.split("&")) {
                String[] tmp = key_value.split("=");
                if ("voter".equals(tmp[0])) {
                    voter = Integer.valueOf(tmp[1]);
                }
                if ("candidate".equals(tmp[0])) {
                    candidate = Short.valueOf(tmp[1]);
                }
            }

            int responseCode = 200;
            if (voter == -1 || candidate == -1) {
                responseCode = 400;
            } else {
                int replication_index = 0;
                short dataIndex = (short) (voter % num_workers);
                ByteBuffer message = ByteBuffer.allocate(msg_len);
                message.putShort(dataIndex).putInt(voter).putShort(candidate);
                long timestamp = System.currentTimeMillis();
                message.putLong(timestamp);
                int done = 0;
                while (replication_index < replicationFactor) {
                    int curr = (dataIndex + replication_index) % num_workers;
                    if (available[curr]) {
                        int res = POST(makeURL(curr, WorkerAction.STORE), message.array());
                        if (res == 200) {
                            done++;
                            synchronized (TimestampLock) {
                                if (timestamp > data_timestamps[curr]) {
                                    data_timestamps[curr] = timestamp;
                                }
                            }
                        } else if (res == 400) {
                            responseCode = 403;
                            break;
                        }
                    }
                    replication_index++;
                }
                if (done == 0 && responseCode != 403) {
                    responseCode = 500;
                }
            }
            String message;
            switch (responseCode) {
                case 200:
                    message = "Vote Submitted";
                    break;
                case 400:
                    message = "Parameters missing";
                    break;
                case 403:
                    message = "Already voted";
                    break;
                default:
                    message = "Service currently unavailable, try again later";
                    break;
            }
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(responseCode, message.length());
                res.write(message.getBytes());
            }
        };

        HttpHandler resultHandler = (HttpExchange ht) -> {
            int[] total_votes = new int[candidates];
            Arrays.fill(total_votes, 0);

            int responseCode = 200;
            for (int data_id = 0; data_id < num_workers; data_id++) {
                int counter = -1;
                String vote_fragment_str = null;
                while (++counter < replicationFactor && vote_fragment_str == null) {
                    int curr = (data_id + counter) % num_workers;
                    if (!available[curr]) {
                        continue;
                    }
                    vote_fragment_str = GET(makeURL(curr, WorkerAction.RESULTS),
                            ByteBuffer.allocate(Integer.BYTES).putInt(data_id).array());
                }
                if (vote_fragment_str != null) {
                    int[] votes_fragment = fromString(vote_fragment_str);
                    for (int i = 0; i < candidates; i++) {
                        total_votes[i] += votes_fragment[i];
                    }
                } else {
                    LOGGER.log(Level.SEVERE, "{0} servers down in a row, "
                            + " please increase replication factor", replicationFactor);
                    responseCode = 500;
                }
            }
            String message = responseCode == 500 ? "DVote down" : Arrays.toString(total_votes);
            try (OutputStream res = ht.getResponseBody()) {
                ht.sendResponseHeaders(responseCode, message.length());
                res.write(message.getBytes());
            }
        };

        int threads = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
        ExecutorService es = Executors.newFixedThreadPool(threads);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(es);
        server.createContext("/vote", mainHandler);
        server.createContext("/results", resultHandler);
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

            return con.getResponseCode();
        } catch (Exception ex) {
            Logger.getLogger(Server.class.getName()).log(Level.SEVERE, null, ex);
        }
        return 500;
    }

    private String GET(String url_str, byte[] message) {
        try {
            URL url = new URL(url_str);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setUseCaches(false);
            con.setRequestMethod("POST");
            if (message != null) {
                con.setDoOutput(true);
                con.getOutputStream().write(message);
                con.getOutputStream().close();
            }
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

    private static int[] fromString(String string) {
        String[] strings = string.split(",");
        int result[] = new int[strings.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = Integer.parseInt(strings[i]);
        }
        return result;
    }

    void start() {
        ping_pong.scheduleAtFixedRate(() -> {
            for (int workerID = 0; workerID < num_workers; workerID++) {
                if (GET(makeURL(workerID, WorkerAction.ALIVE), null) != null) {
                    if (!available[workerID]) {
                        available[workerID] = true;
                        long max_timestamp = 0;
                        synchronized (TimestampLock) {
                            int init = (workerID - replicationFactor + 1);
                            int len = 2 * replicationFactor - 1;
                            for (int i = 0; i < len; i++) {
                                int curr_worker = (init + i) >= 0 ? (init + i)
                                        % num_workers : init + i + num_workers;
                                if (data_timestamps[curr_worker] > max_timestamp) {
                                    max_timestamp = data_timestamps[curr_worker];
                                }
                            }
                        }
                        if (max_timestamp > data_timestamps[workerID]) {
                            data_miss_queue.add(new DataMiss(workerID,
                                    data_timestamps[workerID], max_timestamp));
                        }
                    }
                } else {
                    available[workerID] = false;
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
        if (replicationFactor > 1) {
            inconsistency.scheduleWithFixedDelay(() -> {
                DataMiss dm;
                while ((dm = data_miss_queue.poll()) != null) {
                    long t1 = System.currentTimeMillis();
                    for (int replica = 0; replica < replicationFactor; replica++) {
                        int data_id = dm.worker - replica;
                        if (data_id < 0) {
                            data_id += num_workers;
                        }
                        boolean done = false;
                        for (int i = data_id; i < replicationFactor; i++) {
                            int curr_worker = (data_id + i) % num_workers;
                            if (!available[curr_worker]
                                    || curr_worker == dm.worker) {
                                continue;
                            }
                            byte[] message = ByteBuffer.allocate(msg_resolve_len)
                                    .putShort((short) dm.worker)
                                    .putShort((short) data_id)
                                    .putLong(dm.start).putLong(dm.end)
                                    .array();
                            if (POST(makeURL(curr_worker, WorkerAction.RESOLVE), message) == 200) {
                                done = true;
                                break;
                            }
                        }
                        if (!done) {
                            LOGGER.log(Level.SEVERE, "Inconsistency encountered,"
                                    + " please increase replication factor");
                        }
                    }
                    long t2 = System.currentTimeMillis();
                    System.out.println("Recovery time for worker " + dm.worker + " was " + (t2-t1));
                }
            }, 5, 5, TimeUnit.SECONDS);
        }
        server.start();
    }

    void stop() {
        ping_pong.shutdown();
        server.stop(0);
    }
}
