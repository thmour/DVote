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
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
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
public class Server {

    private static final Logger logger = Logger.getLogger(Server.class.getName());

    private final HttpServer server;
    private final HttpHandler mainHandler;
    private final int replFactor;
    private final String[] workers;
    private final boolean[] available;
    private final int msg_len = 2 * Short.BYTES + Integer.BYTES;
    private final int worker_port;
    private final ScheduledExecutorService ping_pong;

    private enum WorkerAction {
        STORE("/store"),
        DROP("/drop"),
        COPY("/batch_send"),
        RESULTS("/results"),
        ALIVE("/alive");

        private final String value;

        WorkerAction(String value) {
            this.value = value;
        }
    }

    private String makeURL(int wid, WorkerAction uri) {
        return "http://" + workers[wid] + ":" + worker_port + uri.value;
    }

    public Server(int replFactor, String[] workers, int port, int worker_port) throws IOException {
        this.ping_pong = Executors.newSingleThreadScheduledExecutor();
        this.replFactor = replFactor;
        this.workers = workers;
        this.available = new boolean[workers.length];
        this.worker_port = worker_port;

        this.mainHandler = (HttpExchange ht) -> {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(ht.getRequestBody()));
            String query = in.readLine();
            int voter = -1;
            short candidate = -1;
            for (String keyval : query.split("&")) {
                String[] tmp = keyval.split("=");
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
                int wnum = workers.length;
                int count = 0;
                short init = (short) (voter % wnum);
                ByteBuffer message = ByteBuffer.allocate(msg_len);
                message.putShort(init).putInt(voter).putShort(candidate);
                int done = 0;
                while (count < replFactor) {
                    int curr = (init + count) % wnum;
                    if (available[curr]) {
                        int res = POST(makeURL(curr, WorkerAction.STORE), message.array());
                        if (res == 200) {
                            done++;
                        } else if (res == 400) {
                            responseCode = 403;
                            break;
                        }
                    }
                    count++;
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

        int threads = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
        ExecutorService es = Executors.newFixedThreadPool(threads);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(es);
        server.createContext("/vote", mainHandler);
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
            con.setConnectTimeout(700);
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
        String[] strings = string.replace("[", "").replace("]", "").split(", ");
        int result[] = new int[strings.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = Integer.parseInt(strings[i]);
        }
        return result;
    }

    private void calculateVotes() {
        int[] total_votes = new int[6];
        Arrays.fill(total_votes, 0);
        for (int data_id = 0; data_id < workers.length; data_id++) {
            int counter = -1, curr = -1;
            String vote_fragment_str = null;
            while (++counter < replFactor && vote_fragment_str == null) {
                curr = (data_id + counter) % workers.length;
                if (available[curr] == false) {
                    continue;
                }
                vote_fragment_str = GET(makeURL(curr, WorkerAction.RESULTS),
                        ByteBuffer.allocate(Integer.BYTES).putInt(data_id).array());
            }
            if (vote_fragment_str != null) {
                System.out.println("Data: " + data_id + " from " + curr + " : " + vote_fragment_str);
                int[] votes_fragment = fromString(vote_fragment_str);
                for (int i = 0; i < votes_fragment.length; i++) {
                    total_votes[i] += votes_fragment[i];
                }
            } else {
                logger.log(Level.SEVERE, "{0} servers down in a row, "
                        + " please increase replication factor", replFactor);
                System.exit(1);
            }
        }
        System.out.println("Results: " + Arrays.toString(total_votes));
    }

    public void start() {
        ping_pong.scheduleAtFixedRate(() -> {
            for (int workerID = 0; workerID < workers.length; workerID++) {
                available[workerID] = GET(makeURL(workerID, WorkerAction.ALIVE), null) != null;
            }
        }, 0, 1, TimeUnit.SECONDS);
        server.start();
    }

    public void stop() {
        calculateVotes();
        ping_pong.shutdown();
        server.stop(0);
    }
}
